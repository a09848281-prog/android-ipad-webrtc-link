(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const els = {
    gasUrl:$("gasUrl"),gasStatus:$("gasStatus"),saveGas:$("saveGas"),hostMode:$("hostMode"),viewerMode:$("viewerMode"),
    hostPanel:$("hostPanel"),viewerPanel:$("viewerPanel"),startHost:$("startHost"),roomCode:$("roomCode"),copyCode:$("copyCode"),
    joinCode:$("joinCode"),joinRoom:$("joinRoom"),remoteVideo:$("remoteVideo"),localVideo:$("localVideo"),
    placeholder:$("videoPlaceholder"),connectionState:$("connectionState"),mediaState:$("mediaState"),
    ptt:$("pttButton"),disconnect:$("disconnect"),log:$("log"),micToggle:$("micToggle"),videoToggle:$("videoToggle"),remoteVolume:$("remoteVolume"),cameraSwitch:$("cameraSwitch"),resolutionSelect:$("resolutionSelect"),fpsSelect:$("fpsSelect"),zoomRange:$("zoomRange"),zoomValue:$("zoomValue"),torchToggle:$("torchToggle"),adaptiveToggle:$("adaptiveToggle"),requestLocation:$("requestLocation"),locationStatus:$("locationStatus"),locationResult:$("locationResult")
  };
  const ICE = {iceServers:[{urls:["stun:stun.l.google.com:19302","stun:stun1.l.google.com:19302"]}]};
  let role = "android", room = "", pc = null, localStream = null, pttTrack = null;
  let pollTimer = null, seen = new Set(), pendingCandidates = [], remoteDescriptionSet = false;
  let started = false, polling = false, statsTimer = null, connectedAt = 0, previousBytes = null, previousAt = 0, cameraFacing = "environment";
  let reconnectTimer=null,reconnectWatchdog=null,reconnectAttempts=0,reconnecting=false,adaptiveLastLevel="high",locationTimeout=null;

  function log(message){ els.log.textContent = message; }
  function setState(text, kind=""){ els.connectionState.textContent=text; els.connectionState.className="state "+kind; }
  function gas(){ return els.gasUrl.value.trim().replace(/^["'\s]+|["'\s]+$/g, "").replace(/[\u200B-\u200D\uFEFF]/g, ""); }
  function normalizeGasUrl(){
    try {
      const u = new URL(gas());
      const allowedHost = u.hostname === "script.google.com" || u.hostname === "script.googleusercontent.com";
      if (u.protocol !== "https:" || !allowedHost || !/\/exec\/?$/.test(u.pathname)) return "";
      u.hash = "";
      return u.toString().replace(/\/$/, "");
    } catch (_) { return ""; }
  }
  function validGas(){ return !!normalizeGasUrl(); }
  function setRole(next){
    if(started){ log("端末の切り替えは切断後に行ってください。"); return; }
    role=next; els.hostPanel.classList.toggle("hidden",role!=="android"); els.viewerPanel.classList.toggle("hidden",role!=="ipad");
    els.hostMode.classList.toggle("active",role==="android"); els.viewerMode.classList.toggle("active",role==="ipad");
  }
  function makeRoom(){ return String(Math.floor(100000+Math.random()*900000)); }
  function peerConfig(){ return new RTCPeerConnection(ICE); }
  function createId(){ return (crypto.randomUUID ? crypto.randomUUID() : Date.now()+"-"+Math.random().toString(36).slice(2)); }

  async function sendSignal(type,payload,to){
    const body={room,from:role,to,type,payload,id:createId(),time:Date.now()};
    try{
      await fetch(gas(),{method:"POST",mode:"no-cors",headers:{"Content-Type":"text/plain;charset=utf-8"},body:JSON.stringify(body),cache:"no-store"});
    }catch(e){ log("GASへの送信に失敗しました。URLとデプロイ設定を確認してください。"); throw e; }
  }
  function pollUrl(){
    const u=new URL(gas()); u.searchParams.set("action","poll");u.searchParams.set("room",room);u.searchParams.set("role",role);u.searchParams.set("callback","webrtcSignalCallback");u.searchParams.set("_",String(Date.now()));return u.toString();
  }
  function startPolling(){
    stopPolling(); window.webrtcSignalCallback=async(data)=>{
      if(!data || !Array.isArray(data.messages)) return;
      for(const m of data.messages){ if(!m || !m.id || seen.has(m.id)) continue; seen.add(m.id); if(seen.size>500) seen=new Set(Array.from(seen).slice(-250)); await handleSignal(m); }
    };
    pollTimer=setInterval(async()=>{
      if(polling || !started) return; polling=true;
      const script=document.createElement("script");script.src=pollUrl();script.async=true;
      script.onerror=()=>{ log("GASから応答がありません。WebアプリURL・公開範囲を確認してください。"); };
      script.onload=()=>script.remove();document.head.appendChild(script);
      setTimeout(()=>{script.remove();polling=false;},7000);
    },1500);
    const script=document.createElement("script");script.src=pollUrl();script.async=true;script.onload=()=>script.remove();script.onerror=()=>script.remove();document.head.appendChild(script);
  }
  function stopPolling(){if(pollTimer)clearInterval(pollTimer);pollTimer=null;polling=false;}

  async function addRemoteCandidate(candidate){
    if(!pc) return;
    if(!remoteDescriptionSet){pendingCandidates.push(candidate);return;}
    try{await pc.addIceCandidate(new RTCIceCandidate(candidate));}catch(e){console.warn("ICE candidate ignored",e);}
  }
  async function flushCandidates(){
    remoteDescriptionSet=true;
    for(const c of pendingCandidates.splice(0)) await addRemoteCandidate(c);
  }
  async function handleSignal(m){
    if(m.room!==room || m.to!==role) return;
    try{
      if(m.type==="location_result" && role==="ipad"){
        if(locationTimeout){clearTimeout(locationTimeout);locationTimeout=null;}
        const p=m.payload||{};
        if(typeof p.latitude!=="number"||typeof p.longitude!=="number"){els.locationStatus.textContent="位置情報の形式が正しくありません。";return;}
        els.locationResult.textContent="緯度: "+p.latitude.toFixed(6)+" / 経度: "+p.longitude.toFixed(6)+" / 精度: "+(Number.isFinite(p.accuracy)?Math.round(p.accuracy)+" m":"不明");
        els.locationResult.classList.remove("hidden");
        els.locationStatus.textContent="Androidから今回の位置情報を受信しました。表示はこの接続中だけです。";
        els.requestLocation.disabled=false;return;
      }
      if(m.type==="location_denied" && role==="ipad"){
        if(locationTimeout){clearTimeout(locationTimeout);locationTimeout=null;}
        els.locationStatus.textContent="Android側で位置情報の許可が必要です。Androidアプリを開いて位置情報の許可を行ってください。";
        els.requestLocation.disabled=false;return;
      }
      if(m.type==="location_error" && role==="ipad"){
        if(locationTimeout){clearTimeout(locationTimeout);locationTimeout=null;}
        els.locationStatus.textContent="Androidの位置情報を取得できませんでした: "+String((m.payload||{}).reason||"不明");
        els.requestLocation.disabled=false;return;
      }
      if(m.type==="hangup"){
        log("相手側から切断通知を受信しました。");
        await stop({notify:false,reason:"相手側が接続を終了しました。"});
        return;
      }
      if(m.type==="offer" && role==="ipad"){
        await pc.setRemoteDescription(new RTCSessionDescription(m.payload)); await flushCandidates();
        const answer=await pc.createAnswer(); await pc.setLocalDescription(answer);
        await sendSignal("answer",pc.localDescription,"android"); log("応答を送信しました。接続処理中です…");
      }else if(m.type==="answer" && role==="android"){
        if(pc.signalingState!=="have-local-offer") return;
        await pc.setRemoteDescription(new RTCSessionDescription(m.payload)); await flushCandidates(); log("応答を受信しました。接続処理中です…");
      }else if(m.type==="candidate" && m.payload){ await addRemoteCandidate(m.payload); }
    }catch(e){ console.error(e); log("接続情報の処理に失敗しました: "+e.message); }
  }

  function startStats(){
    if(statsTimer) clearInterval(statsTimer);
    statsTimer=setInterval(async()=>{
      if(!pc || pc.connectionState!=="connected") return;
      const now=Date.now(), duration=document.getElementById("duration");
      if(connectedAt){const s=Math.floor((now-connectedAt)/1000);duration.textContent=String(Math.floor(s/60)).padStart(2,"0")+":"+String(s%60).padStart(2,"0");}
      try{
        const report=await pc.getStats();let rtt=null,bytes=null,lost=0,received=0;
        report.forEach(v=>{
          if(v.type==="candidate-pair"&&v.state==="succeeded"&&v.currentRoundTripTime!=null)rtt=v.currentRoundTripTime*1000;
          if(v.type==="inbound-rtp"&&!v.isRemote){if(v.bytesReceived!=null)bytes=(bytes||0)+v.bytesReceived;lost+=v.packetsLost||0;received+=v.packetsReceived||0;}
        });
        document.getElementById("rtt").textContent=rtt==null?"—":Math.round(rtt)+" ms";
        document.getElementById("packetLoss").textContent=(lost+received)?(100*lost/(lost+received)).toFixed(1)+"%":"—";
        adaptQuality(rtt==null?0:rtt,lost+received===0?0:lost/(lost+received)*100);
        if(bytes!=null&&previousBytes!=null&&previousAt)document.getElementById("bitrate").textContent=Math.max(0,(bytes-previousBytes)*8/((now-previousAt)/1000)/1000).toFixed(0)+" kbps";
        if(bytes!=null){previousBytes=bytes;previousAt=now;}
      }catch(_){}
    },2000);
  }

  function setupPeer(){
    pc=peerConfig();
    pc.onicecandidate=(event)=>{if(event.candidate) sendSignal("candidate",event.candidate.toJSON(),role==="android"?"ipad":"android").catch(()=>{});};
    pc.onconnectionstatechange=()=>{
      if(!pc)return;
      const state=pc.connectionState;
      if(state==="connected"){
        clearReconnectTimer();reconnectAttempts=0;reconnecting=false;
        setState("接続中","ok");connectedAt=connectedAt||Date.now();startStats();log("端末同士が接続されました。");
      }else if(state==="connecting"){setState(reconnecting?"再接続中":"接続処理中","wait");}
      else if(state==="failed"){
        setState("再接続を準備中","wait");scheduleReconnect(0);
      }else if(state==="disconnected"){
        setState("接続が不安定です","wait");scheduleReconnect(5000);
      }else if(state==="closed"){setState("未接続");}
    };
    pc.ontrack=(event)=>{
      let stream=event.streams && event.streams[0];
      if(!stream){stream=new MediaStream([event.track]);}
      els.remoteVideo.srcObject=stream; els.remoteVideo.volume=Number(els.remoteVolume.value);els.remoteVolume.disabled=false; els.placeholder.classList.add("hidden");
      els.mediaState.textContent="映像・音声を受信中";
      els.remoteVideo.play().catch(()=>{log("映像は受信しています。画面をタップして再生してください。");});
    };
    pc.oniceconnectionstatechange=()=>{if(pc.iceConnectionState==="checking")setState("経路確認中","wait");};
  }
  function clearReconnectTimer(){
    if(reconnectTimer){clearTimeout(reconnectTimer);reconnectTimer=null;}
    if(reconnectWatchdog){clearTimeout(reconnectWatchdog);reconnectWatchdog=null;}
  }
  function scheduleReconnect(delay){
    if(!started||role!=="android"||reconnectTimer||reconnecting)return;
    if(reconnectAttempts>=5){
      setState("再接続できません");
      log("自動再接続を5回試しましたが復旧できませんでした。手動で接続し直してください。");
      return;
    }
    const wait=delay==null?Math.min(2000*Math.pow(2,reconnectAttempts),15000):delay;
    setState("再接続を待機中","wait");
    log("通信が切れました。"+Math.ceil(wait/1000)+"秒後に再接続を試します（"+(reconnectAttempts+1)+"/5）。");
    reconnectTimer=setTimeout(()=>{reconnectTimer=null;restartIce().catch(error=>{
      reconnecting=false;log("再接続処理に失敗しました: "+error.message);scheduleReconnect();
    });},wait);
  }
  async function restartIce(){
    if(!started||role!=="android"||!pc||reconnecting)return;
    reconnecting=true;reconnectAttempts++;
    setState("再接続中","wait");
    try{
      if(pc.signalingState!=="stable"){
        await new Promise(resolve=>setTimeout(resolve,1000));
        if(!pc||!started)throw new Error("接続が終了しました。");
        if(pc.signalingState!=="stable")throw new Error("シグナリング状態が安定していません。");
      }
      remoteDescriptionSet=false;pendingCandidates=[];
      const offer=await pc.createOffer({iceRestart:true});
      await pc.setLocalDescription(offer);
      await sendSignal("offer",pc.localDescription,"ipad");
      log("ICE再起動Offerを送信しました。応答を待っています…");
      reconnectWatchdog=setTimeout(()=>{reconnectWatchdog=null;if(!started||!reconnecting)return;reconnecting=false;log("再接続応答がタイムアウトしました。再試行します。");scheduleReconnect();},15000);
    }catch(error){reconnecting=false;throw error;}
  }

  function videoTrack(){return localStream?localStream.getVideoTracks()[0]||null:null;}
  function updateCameraCapabilities(){
    const track=videoTrack();if(!track)return;
    let caps={};try{caps=track.getCapabilities?track.getCapabilities():{};}catch(_){caps={};}const zoom=caps.zoom;
    if(zoom){els.zoomRange.min=String(zoom.min);els.zoomRange.max=String(zoom.max);els.zoomRange.step=String(zoom.step||0.1);els.zoomRange.value=String(Math.max(zoom.min,Math.min(zoom.max,1)));els.zoomRange.disabled=false;els.zoomValue.textContent=els.zoomRange.value+"x";}
    else{els.zoomRange.disabled=true;els.zoomValue.textContent="非対応";}
    els.torchToggle.disabled=caps.torch!==true;els.torchToggle.checked=false;
    els.resolutionSelect.disabled=false;els.fpsSelect.disabled=false;els.adaptiveToggle.disabled=false;
  }
  function setCameraControlsEnabled(enabled){
    [els.resolutionSelect,els.fpsSelect,els.adaptiveToggle].forEach(el=>el.disabled=!enabled);
    if(!enabled){els.zoomRange.disabled=true;els.torchToggle.disabled=true;els.torchToggle.checked=false;}
    else updateCameraCapabilities();
  }
  async function applyCameraProfile(){
    const track=videoTrack();if(!track||!started)return;
    const [width,height]=els.resolutionSelect.value.split("x").map(Number),fps=Number(els.fpsSelect.value);
    try{await track.applyConstraints({width:{ideal:width},height:{ideal:height},frameRate:{ideal:fps,max:fps}});log("カメラ設定を適用しました（実際の値は端末により異なります）。");}
    catch(error){log("選択した画質はこの端末で利用できません: "+error.message);}
    updateSenderBitrate();
  }
  async function updateSenderBitrate(level){
    if(!pc)return;const sender=pc.getSenders().find(item=>item.track&&item.track.kind==="video");
    if(!sender||!sender.getParameters||!sender.setParameters)return;
    try{const params=sender.getParameters();if(!params.encodings||!params.encodings.length)return;
      const selected=level||(els.adaptiveToggle.checked?adaptiveLastLevel:"high");
      params.encodings[0].maxBitrate=selected==="low"?350000:selected==="medium"?900000:2500000;
      await sender.setParameters(params);
    }catch(error){console.warn("bitrate adjustment unavailable",error);}
  }
  async function applyZoom(){
    const track=videoTrack();if(!track)return;
    try{await track.applyConstraints({advanced:[{zoom:Number(els.zoomRange.value)}]});els.zoomValue.textContent=els.zoomRange.value+"x";}
    catch(error){log("ズームを適用できません: "+error.message);}
  }
  async function applyTorch(){
    const track=videoTrack();if(!track)return;
    try{await track.applyConstraints({advanced:[{torch:els.torchToggle.checked}]});}
    catch(error){els.torchToggle.checked=false;log("ライト機能はこのカメラで利用できません: "+error.message);}
  }
  function adaptQuality(rtt,loss){
    if(!els.adaptiveToggle.checked)return;
    const level=(loss>8||rtt>500)?"low":(loss>3||rtt>250)?"medium":"high";
    if(level!==adaptiveLastLevel){adaptiveLastLevel=level;updateSenderBitrate(level);log("通信状況に合わせて送信ビットレートを"+({low:"低",medium:"中",high:"高"}[level])+"に調整しました。");}
  }

  async function getMedia(constraints){
    if(!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error("このブラウザではカメラ・マイクを利用できません。HTTPSのページで開いてください。");
    return navigator.mediaDevices.getUserMedia(constraints);
  }
  async function startAndroid(){
    if(!validGas()) throw new Error("先に正しいGAS WebアプリURL（/exec）を保存してください。");
    els.startHost.disabled=true; log("Androidのカメラ・マイクを起動しています…");
    cameraFacing="environment";localStream=await getMedia({audio:{echoCancellation:true,noiseSuppression:true},video:{facingMode:{ideal:cameraFacing},width:{ideal:Number(els.resolutionSelect.value.split("x")[0])},height:{ideal:Number(els.resolutionSelect.value.split("x")[1])},frameRate:{ideal:Number(els.fpsSelect.value)}}});
    els.localVideo.srcObject=localStream; room=makeRoom(); els.roomCode.textContent=room;els.copyCode.disabled=false;
    setupPeer(); localStream.getTracks().forEach(t=>pc.addTrack(t,localStream));
    reconnectAttempts=0;reconnecting=false;clearReconnectTimer();
    started=true; els.disconnect.disabled=false; els.requestLocation.disabled=false; els.micToggle.disabled=false;els.videoToggle.disabled=false;els.cameraSwitch.disabled=false;setCameraControlsEnabled(true);setState("接続待機中","wait");startPolling();
    const offer=await pc.createOffer();await pc.setLocalDescription(offer);await sendSignal("offer",pc.localDescription,"ipad");
    log("接続待機中です。6桁コードをiPadに入力してください。");
  }
  async function joinAndroid(){
    if(!validGas()) throw new Error("先に正しいGAS WebアプリURL（/exec）を保存してください。");
    room=els.joinCode.value.replace(/\D/g,""); if(!/^\d{6}$/.test(room)) throw new Error("6桁の接続コードを入力してください。");
    els.joinRoom.disabled=true;log("iPadのマイク使用許可を待っています…");
    localStream=await getMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false});
    pttTrack=localStream.getAudioTracks()[0];pttTrack.enabled=false;
    setupPeer();pc.addTrack(pttTrack,localStream);
    reconnectAttempts=0;reconnecting=false;clearReconnectTimer();
    started=true;els.disconnect.disabled=false;els.requestLocation.disabled=false;els.ptt.disabled=false;els.micToggle.disabled=false;els.videoToggle.disabled=true;setState("Androidを検索中","wait");startPolling();
    log("Android側からの接続情報を待っています…");
  }
  async function stop(options={}){
    const notify=options.notify!==false;
    const reason=options.reason||"切断しました。";
    const wasStarted=started;
    clearReconnectTimer();reconnecting=false;reconnectAttempts=0;
    if(notify&&wasStarted&&room){
      try{await sendSignal("hangup",{reason:"user-disconnect"},role==="android"?"ipad":"android");}
      catch(_){log("切断通知を送信できませんでした。ローカル側の終了処理を続行します。");}
    }
    started=false;if(locationTimeout){clearTimeout(locationTimeout);locationTimeout=null;}els.requestLocation.disabled=true;els.locationResult.textContent="";els.locationResult.classList.add("hidden");els.locationStatus.textContent="接続後、ボタンを押したときだけAndroidへ1回だけ要求します。自動取得・履歴保存はしません。";stopPolling();if(statsTimer)clearInterval(statsTimer);statsTimer=null;connectedAt=0;previousBytes=null;previousAt=0;["duration","rtt","bitrate","packetLoss"].forEach(id=>{const e=document.getElementById(id);if(e)e.textContent=id==="duration"?"--:--":"—";});
    if(pc){pc.ontrack=null;pc.onicecandidate=null;pc.onconnectionstatechange=null;pc.close();pc=null;}
    if(localStream){localStream.getTracks().forEach(t=>t.stop());localStream=null;}
    pttTrack=null;pendingCandidates=[];remoteDescriptionSet=false;seen.clear();
    els.remoteVideo.srcObject=null;els.localVideo.srcObject=null;els.placeholder.classList.remove("hidden");
    els.mediaState.textContent="映像・音声：未受信";els.ptt.disabled=true;els.micToggle.disabled=true;els.videoToggle.disabled=true;els.remoteVolume.disabled=true;els.remoteVolume.value="1";els.remoteVideo.volume=1;els.micToggle.textContent="🎙️ マイクをミュート";els.videoToggle.textContent="📷 映像を停止";els.ptt.classList.remove("active");els.disconnect.disabled=true;
    els.startHost.disabled=false;els.joinRoom.disabled=false;els.cameraSwitch.disabled=true;setCameraControlsEnabled(false);els.roomCode.textContent="------";els.copyCode.disabled=true;
    setState("未接続");log(reason);
  }
  function pttDown(e){if(role!=="ipad"||!pttTrack||!started)return;e.preventDefault();pttTrack.enabled=true;els.ptt.classList.add("active");els.ptt.textContent="🔴 送信中…（離すとミュート）";}
  function pttUp(){if(pttTrack){pttTrack.enabled=false;els.ptt.classList.remove("active");els.ptt.textContent="🎙️ 押している間だけ話す";}}

  els.saveGas.addEventListener("click",()=>{
    if(!validGas()){els.gasStatus.textContent="URLを確認してください";log("GASの「ウェブアプリ URL」を使ってください。例：https://script.google.com/macros/s/.../exec（末尾は /exec）。URLの前後の空白や引用符は自動で除去します。");return;}
    const normalized = normalizeGasUrl();
    if (!normalized) { els.gasStatus.textContent="URLを確認してください"; log("GASの「ウェブアプリ URL」をコピーしてください。末尾が /exec のURLが必要です。"); return; }
    els.gasUrl.value = normalized;
    localStorage.setItem("webrtcGasUrl",normalized);els.gasStatus.textContent="保存済み ✓";log("GAS URLをこの端末に保存しました。");
  });
  els.gasUrl.value=localStorage.getItem("webrtcGasUrl")||"";if(els.gasUrl.value)els.gasStatus.textContent="保存済み";
  els.hostMode.addEventListener("click",()=>setRole("android"));els.viewerMode.addEventListener("click",()=>setRole("ipad"));
  els.startHost.addEventListener("click",()=>startAndroid().catch(e=>{console.error(e);log(e.message);els.startHost.disabled=false;}));
  els.joinRoom.addEventListener("click",()=>joinAndroid().catch(e=>{console.error(e);log(e.message);els.joinRoom.disabled=false;}));
  els.copyCode.addEventListener("click",async()=>{try{await navigator.clipboard.writeText(room);log("接続コードをコピーしました。");}catch(_){log("コードを長押ししてコピーしてください。");}});
  els.disconnect.addEventListener("click",stop);
  els.requestLocation.addEventListener("click",async()=>{
    if(role!=="ipad"||!started||!pc||pc.connectionState!=="connected"){els.locationStatus.textContent="Androidと接続してから押してください。";return;}
    els.requestLocation.disabled=true;els.locationResult.textContent="";els.locationResult.classList.add("hidden");
    els.locationStatus.textContent="Androidへ1回限りの取得を要求しています…";
    try{await sendSignal("location_request",{oneShot:true},"android");}
    catch(error){els.locationStatus.textContent="要求を送信できませんでした: "+error.message;els.requestLocation.disabled=false;return;}
    locationTimeout=setTimeout(()=>{locationTimeout=null;els.locationStatus.textContent="応答がありません。Androidの接続・位置情報許可を確認してください。";els.requestLocation.disabled=false;},20000);
  });

  els.micToggle.addEventListener("click",()=>{
    const tracks=localStream?localStream.getAudioTracks():[];
    if(!tracks.length){log("この端末のマイクトラックがありません。");return;}
    const enabled=!tracks[0].enabled;
    tracks.forEach(track=>track.enabled=enabled);
    els.micToggle.textContent=enabled?"🎙️ マイクをミュート":"🔇 マイクのミュート解除";
  });
  els.videoToggle.addEventListener("click",()=>{
    const tracks=localStream?localStream.getVideoTracks():[];
    if(!tracks.length){log("この端末のカメラ映像はありません。");return;}
    const enabled=!tracks[0].enabled;
    tracks.forEach(track=>track.enabled=enabled);
    els.videoToggle.textContent=enabled?"📷 映像を停止":"▶️ 映像を再開";
  });
  els.remoteVolume.addEventListener("input",()=>{els.remoteVideo.volume=Number(els.remoteVolume.value);});
  els.cameraSwitch.addEventListener("click",async()=>{
    if(role!=="android"||!localStream||!pc||!started)return;
    els.cameraSwitch.disabled=true;
    const next=cameraFacing==="environment"?"user":"environment";
    let replacement=null;
    try{
      try{replacement=await getMedia({audio:false,video:{facingMode:{ideal:next},width:{ideal:Number(els.resolutionSelect.value.split("x")[0])},height:{ideal:Number(els.resolutionSelect.value.split("x")[1])},frameRate:{ideal:Number(els.fpsSelect.value)}}});}
      catch(firstError){throw new Error("切替先カメラを起動できません: "+firstError.message);}
      const newTrack=replacement.getVideoTracks()[0];
      if(!newTrack)throw new Error("切替先カメラの映像トラックを取得できませんでした。");
      const sender=pc.getSenders().find(item=>item.track&&item.track.kind==="video");
      if(!sender)throw new Error("送信中の映像トラックが見つかりません。");
      await sender.replaceTrack(newTrack);
      const oldTracks=localStream.getVideoTracks();
      oldTracks.forEach(track=>{localStream.removeTrack(track);track.stop();});
      localStream.addTrack(newTrack);
      els.localVideo.srcObject=localStream;
      cameraFacing=next;
      updateCameraCapabilities();
      log(next==="user"?"前面カメラに切り替えました。":"背面カメラに切り替えました。");
    }catch(error){if(replacement)replacement.getTracks().forEach(track=>track.stop());log("カメラ切替に失敗しました: "+error.message);}
    finally{els.cameraSwitch.disabled=false;}
  });
  els.resolutionSelect.addEventListener("change",applyCameraProfile);
  els.fpsSelect.addEventListener("change",applyCameraProfile);
  els.zoomRange.addEventListener("input",applyZoom);
  els.torchToggle.addEventListener("change",applyTorch);
  els.adaptiveToggle.addEventListener("change",()=>{adaptiveLastLevel="high";updateSenderBitrate();log(els.adaptiveToggle.checked?"自動画質調整を有効にしました。":"自動画質調整を停止しました。");});
  els.ptt.addEventListener("pointerdown",pttDown);["pointerup","pointercancel","pointerleave","lostpointercapture"].forEach(ev=>els.ptt.addEventListener(ev,pttUp));
  document.addEventListener("visibilitychange",()=>{if(document.hidden && pttTrack)pttUp();});
  if ("serviceWorker" in navigator && location.protocol === "https:") {
    window.addEventListener("load", () => navigator.serviceWorker.register("./sw.js").catch(err => console.warn("PWA worker registration failed", err)));
  }
  window.addEventListener("beforeunload",()=>{stopPolling();if(localStream)localStream.getTracks().forEach(t=>t.stop());});
})();