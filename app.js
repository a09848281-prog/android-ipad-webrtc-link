(() => {
  "use strict";
  // WebRTC Link v2: authenticated device pairing, presence, and one-shot location.
  const $ = (id) => document.getElementById(id);
  const els = {
    gasUrl:$("gasUrl"),gasStatus:$("gasStatus"),saveGas:$("saveGas"),hostMode:$("hostMode"),viewerMode:$("viewerMode"),
    hostPanel:$("hostPanel"),viewerPanel:$("viewerPanel"),startHost:$("startHost"),roomCode:$("roomCode"),copyCode:$("copyCode"),
    joinCode:$("joinCode"),joinRoom:$("joinRoom"),remoteVideo:$("remoteVideo"),localVideo:$("localVideo"),
    placeholder:$("videoPlaceholder"),connectionState:$("connectionState"),mediaState:$("mediaState"),
    ptt:$("pttButton"),disconnect:$("disconnect"),log:$("log"),micToggle:$("micToggle"),videoToggle:$("videoToggle"),remoteVolume:$("remoteVolume"),cameraSwitch:$("cameraSwitch"),resolutionSelect:$("resolutionSelect"),fpsSelect:$("fpsSelect"),zoomRange:$("zoomRange"),zoomValue:$("zoomValue"),torchToggle:$("torchToggle"),adaptiveToggle:$("adaptiveToggle"),requestLocation:$("requestLocation"),locationStatus:$("locationStatus"),locationResult:$("locationResult"),devicePresence:$("devicePresence"),runDiagnostics:$("runDiagnostics"),diagnosticResult:$("diagnosticResult"),pairCodeInput:$("pairCodeInput"),registerDevice:$("registerDevice"),registeredDeviceSelect:$("registeredDeviceSelect"),renameDevice:$("renameDevice"),favoriteDevice:$("favoriteDevice"),removeDevice:$("removeDevice"),viewDeviceHistory:$("viewDeviceHistory"),deviceManagerStatus:$("deviceManagerStatus"),registeredDeviceStatus:$("registeredDeviceStatus"),refreshDeviceStatus:$("refreshDeviceStatus"),connectRegisteredDevice:$("connectRegisteredDevice")
  };
  const ICE = {iceServers:[{urls:["stun:stun.l.google.com:19302","stun:stun1.l.google.com:19302"]}]};
  let role = "ipad", room = "", pc = null, localStream = null, pttTrack = null;
  let pollTimer = null, seen = new Set(), pendingCandidates = [], remoteDescriptionSet = false;
  let started = false, polling = false, statsTimer = null, connectedAt = 0, previousBytes = null, previousAt = 0, cameraFacing = "environment";
  let reconnectTimer=null,reconnectWatchdog=null,reconnectAttempts=0,reconnecting=false,adaptiveLastLevel="high",locationTimeout=null,deviceStatusTimer=null;
  let registeredDevices=loadRegisteredDevices(),activeDevice=null,authDeviceId="",authToken="",activePresence=null;

  function loadRegisteredDevices(){
    try{const data=JSON.parse(localStorage.getItem("webrtcRegisteredDevices")||"[]");return Array.isArray(data)?data.filter(x=>x&&typeof x.deviceId==="string"&&typeof x.authToken==="string"):[];}
    catch(_){return [];}
  }
  function saveRegisteredDevices(){localStorage.setItem("webrtcRegisteredDevices",JSON.stringify(registeredDevices));}
  function renderRegisteredDevices(){
    const select=els.registeredDeviceSelect;if(!select)return;
    select.replaceChildren();
    if(!registeredDevices.length){const option=document.createElement("option");option.value="";option.textContent="端末を登録してください";select.appendChild(option);}
    registeredDevices.forEach(device=>{
      const option=document.createElement("option");option.value=device.deviceId;
      option.textContent=(device.favorite?"★ ":"")+(device.nickname||device.label||"Android端末");
      select.appendChild(option);
    });
    const selected=registeredDevices.find(d=>d.deviceId===authDeviceId)||registeredDevices[0]||null;
    if(selected){select.value=selected.deviceId;setActiveDevice(selected);}
    else{activeDevice=null;authDeviceId="";authToken="";updateDeviceActions();}
  }
  function setActiveDevice(device){
    activeDevice=device||null;authDeviceId=device?device.deviceId:"";authToken=device?device.authToken:"";activePresence=null;
    if(els.registeredDeviceStatus)els.registeredDeviceStatus.textContent=device?"状態を確認しています…":"端末を選択してください。";
    updateDeviceActions();
    if(device)fetchRegisteredDeviceStatus().catch(error=>{if(els.registeredDeviceStatus)els.registeredDeviceStatus.textContent="状態確認失敗: "+error.message;activePresence=null;updateDeviceActions();});
  }
  function updateDeviceActions(){
    const enabled=!!activeDevice;
    [els.renameDevice,els.favoriteDevice,els.removeDevice,els.viewDeviceHistory,els.refreshDeviceStatus].forEach(button=>{if(button)button.disabled=!enabled;});
    if(els.connectRegisteredDevice)els.connectRegisteredDevice.disabled=!(enabled&&activePresence&&activePresence.online&&activePresence.room);
    if(els.favoriteDevice)els.favoriteDevice.textContent=activeDevice&&activeDevice.favorite?"★ お気に入り解除":"☆ お気に入り";
  }
  async function fetchRegisteredDeviceStatus(){
    if(!activeDevice)throw new Error("登録端末を選択してください。");
    if(!validGas())throw new Error("GAS /exec URLを保存してください。");
    const result=await new Promise((resolve,reject)=>{
      const callback="webrtcDeviceStatusCallback";const script=document.createElement("script");
      const timer=setTimeout(()=>{cleanup();reject(new Error("端末状態の確認がタイムアウトしました。"));},8000);
      function cleanup(){clearTimeout(timer);script.remove();try{delete window[callback];}catch(_){}}
      window[callback]=(data)=>{cleanup();if(data&&data.ok)resolve(data);else reject(new Error((data&&data.error)||"状態を取得できませんでした。"));};
      const url=new URL(normalizeGasUrl());url.searchParams.set("action","device_status");url.searchParams.set("deviceId",activeDevice.deviceId);url.searchParams.set("authToken",activeDevice.authToken);url.searchParams.set("callback",callback);url.searchParams.set("_",String(Date.now()));
      script.src=url.toString();script.onerror=()=>{cleanup();reject(new Error("GASに接続できませんでした。"));};document.head.appendChild(script);
    });
    activePresence=result;
    const status=els.registeredDeviceStatus;
    if(result.online&&result.room){
      const battery=result.batteryPercent!==null&&result.batteryPercent!==undefined?Math.round(Number(result.batteryPercent))+"%":"不明";
      status.textContent="🟢 オンライン / "+(result.deviceLabel||activeDevice.nickname||"Android")+
        " / 電池 "+battery+" / "+(result.networkType||"通信方式不明")+" / 接続コード "+result.room;
    }else status.textContent="⚪ オフライン：AndroidアプリでWebRTC待機を開始してください。";
    if(activeDevice&&activeDevice.lastConnected)status.textContent+=" / 最終接続："+new Date(activeDevice.lastConnected).toLocaleString();
    updateDeviceActions();return result;
  }

  async function revokeRegisteredDevice(device){
    return await new Promise((resolve,reject)=>{
      const callback="webrtcUnpairCallback";const script=document.createElement("script");
      const timer=setTimeout(()=>{cleanup();reject(new Error("登録解除がタイムアウトしました。"));},8000);
      function cleanup(){clearTimeout(timer);script.remove();try{delete window[callback];}catch(_){}}
      window[callback]=(data)=>{cleanup();if(data&&data.ok)resolve(true);else reject(new Error((data&&data.error)||"登録解除に失敗しました。"));};
      const url=new URL(normalizeGasUrl());url.searchParams.set("action","unpair");url.searchParams.set("deviceId",device.deviceId);url.searchParams.set("authToken",device.authToken);url.searchParams.set("callback",callback);url.searchParams.set("_",String(Date.now()));
      script.src=url.toString();script.onerror=()=>{cleanup();reject(new Error("GASに接続できませんでした。"));};document.head.appendChild(script);
    });
  }

  async function registerPairedDevice(){
    const code=els.pairCodeInput.value.replace(/\D/g,"");
    if(!/^\d{8}$/.test(code)){els.deviceManagerStatus.textContent="8桁のペアリングコードを入力してください。";return;}
    if(!validGas()){els.deviceManagerStatus.textContent="先にGAS /exec URLを保存してください。";return;}
    els.registerDevice.disabled=true;els.deviceManagerStatus.textContent="ペアリングを確認しています…";
    const result=await new Promise((resolve,reject)=>{
      const callback="webrtcPairCallback";const script=document.createElement("script");
      const timer=setTimeout(()=>{cleanup();reject(new Error("ペアリング応答がタイムアウトしました。"));},10000);
      function cleanup(){clearTimeout(timer);script.remove();try{delete window[callback];}catch(_){}}
      window[callback]=(data)=>{cleanup();if(data&&data.ok)resolve(data);else reject(new Error((data&&data.error)||"コードが無効か期限切れです。"));};
      const url=new URL(normalizeGasUrl());url.searchParams.set("action","pair_complete");url.searchParams.set("code",code);url.searchParams.set("callback",callback);url.searchParams.set("_",String(Date.now()));
      script.src=url.toString();script.onerror=()=>{cleanup();reject(new Error("GASに接続できませんでした。"));};document.head.appendChild(script);
    });
    const existing=registeredDevices.find(d=>d.deviceId===result.deviceId);
    const device={deviceId:result.deviceId,authToken:result.authToken,label:String(result.label||"Android端末"),nickname:existing?existing.nickname:String(result.label||"Android端末"),favorite:existing?!!existing.favorite:false,addedAt:existing?existing.addedAt:Date.now(),lastConnected:existing?existing.lastConnected:null,history:existing?existing.history||[]:[]};
    if(existing)registeredDevices=registeredDevices.map(d=>d.deviceId===device.deviceId?device:d);else registeredDevices.push(device);
    saveRegisteredDevices();setActiveDevice(device);renderRegisteredDevices();els.registeredDeviceSelect.value=device.deviceId;
    els.deviceManagerStatus.textContent="登録しました："+(device.nickname||device.label)+"。このiPadに認証情報を保存しました。";
    els.pairCodeInput.value="";
  }

  function log(message){ els.log.textContent = message; }
  function recordDeviceConnection(){
    if(role!=="ipad"||!activeDevice)return;
    const now=Date.now();
    registeredDevices=registeredDevices.map(device=>{
      if(device.deviceId!==activeDevice.deviceId)return device;
      const history=Array.isArray(device.history)?device.history:[];
      history.unshift({time:now,room:room});
      return {...device,lastConnected:now,history:history.slice(0,30)};
    });
    saveRegisteredDevices();activeDevice=registeredDevices.find(d=>d.deviceId===authDeviceId)||activeDevice;
    els.deviceManagerStatus.textContent="接続履歴を保存しました。最終接続："+new Date(now).toLocaleString();
  }
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
    if(!authDeviceId||!authToken)throw new Error("登録済みAndroid端末を選択してください。");
    const body={room,deviceId:authDeviceId,authToken,from:role,to,type,payload,id:createId(),time:Date.now()};
    try{
      await fetch(gas(),{method:"POST",mode:"no-cors",headers:{"Content-Type":"text/plain;charset=utf-8"},body:JSON.stringify(body),cache:"no-store"});
    }catch(e){ log("GASへの送信に失敗しました。URLとデプロイ設定を確認してください。"); throw e; }
  }
  function pollUrl(){
    const u=new URL(gas()); u.searchParams.set("action","poll");u.searchParams.set("room",room);u.searchParams.set("role",role);u.searchParams.set("deviceId",authDeviceId);u.searchParams.set("authToken",authToken);u.searchParams.set("callback","webrtcSignalCallback");u.searchParams.set("_",String(Date.now()));return u.toString();
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
      if(m.type==="device_status" && role==="ipad"){
        const p=m.payload||{};
        const battery=p.batteryPercent!==null&&p.batteryPercent!==undefined&&Number.isFinite(Number(p.batteryPercent))?Math.round(Number(p.batteryPercent))+"%":"不明";
        const label=String(p.deviceLabel||"Android端末").slice(0,60);
        const network=String(p.networkType||"不明").slice(0,30);
        els.devicePresence.textContent="🟢 "+label+"：オンライン / 電池 "+battery+" / "+network;
        els.devicePresence.classList.add("online");
        if(activeDevice&&activeDevice.deviceId===authDeviceId){activePresence={...p,online:true};updateDeviceActions();}
        if(deviceStatusTimer)clearTimeout(deviceStatusTimer);
        deviceStatusTimer=setTimeout(()=>{
          els.devicePresence.textContent="⚪ Android端末：オフラインまたは応答なし";
          els.devicePresence.classList.remove("online");
          if(activeDevice&&activeDevice.deviceId===authDeviceId){activePresence={online:false};updateDeviceActions();}
        },50000);
        return;
      }
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
        setState("接続中","ok");if(role==="ipad")els.cameraSwitch.disabled=false;connectedAt=connectedAt||Date.now();recordDeviceConnection();startStats();log("端末同士が接続されました。");
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
    if(!authDeviceId||!authToken)throw new Error("先に端末登録を行ってください。");
    if(!validGas()) throw new Error("先に正しいGAS WebアプリURL（/exec）を保存してください。");
    els.startHost.disabled=true; log("Androidのカメラ・マイクを起動しています…");
    cameraFacing="environment";localStream=await getMedia({audio:{echoCancellation:true,noiseSuppression:true},video:{facingMode:{ideal:cameraFacing},width:{ideal:Number(els.resolutionSelect.value.split("x")[0])},height:{ideal:Number(els.resolutionSelect.value.split("x")[1])},frameRate:{ideal:Number(els.fpsSelect.value)}}});
    els.localVideo.srcObject=localStream; room=makeRoom(); els.roomCode.textContent=room;els.copyCode.disabled=false;
    setupPeer(); localStream.getTracks().forEach(t=>pc.addTrack(t,localStream));
    reconnectAttempts=0;reconnecting=false;clearReconnectTimer();
    started=true; els.disconnect.disabled=false; els.requestLocation.disabled=role!=="ipad"; els.micToggle.disabled=false;els.videoToggle.disabled=false;els.cameraSwitch.disabled=false;setCameraControlsEnabled(true);setState("接続待機中","wait");startPolling();
    const offer=await pc.createOffer();await pc.setLocalDescription(offer);await sendSignal("offer",pc.localDescription,"ipad");
    log("接続待機中です。6桁コードをiPadに入力してください。");
  }
  async function joinAndroid(){
    if(!activeDevice||!authDeviceId||!authToken)throw new Error("先に登録済みAndroid端末を選択してください。");
    if(!validGas()) throw new Error("先に正しいGAS WebアプリURL（/exec）を保存してください。");
    room=els.joinCode.value.replace(/\D/g,""); if(!/^\d{6}$/.test(room)) throw new Error("6桁の接続コードを入力してください。");
    els.joinRoom.disabled=true;log("iPadのマイク使用許可を待っています…");
    localStream=await getMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false});
    pttTrack=localStream.getAudioTracks()[0];pttTrack.enabled=false;
    setupPeer();pc.addTrack(pttTrack,localStream);
    reconnectAttempts=0;reconnecting=false;clearReconnectTimer();
    started=true;els.disconnect.disabled=false;els.requestLocation.disabled=role!=="ipad";els.cameraSwitch.disabled=role!=="ipad";els.devicePresence.textContent="🟡 Android端末の状態を確認中…";els.ptt.disabled=false;els.micToggle.disabled=false;els.videoToggle.disabled=true;setState("Androidを検索中","wait");startPolling();
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
    started=false;if(locationTimeout){clearTimeout(locationTimeout);locationTimeout=null;}if(deviceStatusTimer){clearTimeout(deviceStatusTimer);deviceStatusTimer=null;}els.devicePresence.textContent="📱 Android端末：状態不明";els.devicePresence.classList.remove("online");els.requestLocation.disabled=true;els.cameraSwitch.disabled=true;els.locationResult.textContent="";els.locationResult.classList.add("hidden");els.locationStatus.textContent="接続後、ボタンを押したときだけAndroidへ1回だけ要求します。自動取得・履歴保存はしません。";stopPolling();if(statsTimer)clearInterval(statsTimer);statsTimer=null;connectedAt=0;previousBytes=null;previousAt=0;["duration","rtt","bitrate","packetLoss"].forEach(id=>{const e=document.getElementById(id);if(e)e.textContent=id==="duration"?"--:--":"—";});
    if(pc){pc.ontrack=null;pc.onicecandidate=null;pc.onconnectionstatechange=null;pc.close();pc=null;}
    if(localStream){localStream.getTracks().forEach(t=>t.stop());localStream=null;}
    pttTrack=null;pendingCandidates=[];remoteDescriptionSet=false;seen.clear();
    els.remoteVideo.srcObject=null;els.localVideo.srcObject=null;els.placeholder.classList.remove("hidden");
    els.mediaState.textContent="映像・音声：未受信";els.cameraSwitch.disabled=true;els.ptt.disabled=true;els.micToggle.disabled=true;els.videoToggle.disabled=true;els.remoteVolume.disabled=true;els.remoteVolume.value="1";els.remoteVideo.volume=1;els.micToggle.textContent="🎙️ マイクをミュート";els.videoToggle.textContent="📷 映像を停止";els.ptt.classList.remove("active");els.disconnect.disabled=true;
    els.startHost.disabled=false;els.joinRoom.disabled=false;els.cameraSwitch.disabled=true;setCameraControlsEnabled(false);els.roomCode.textContent="------";els.copyCode.disabled=true;
    setState("未接続");log(reason);
  }
  function pttDown(e){if(role!=="ipad"||!pttTrack||!started)return;e.preventDefault();pttTrack.enabled=true;els.ptt.classList.add("active");els.ptt.textContent="🔴 送信中…（離すとミュート）";}
  function pttUp(){if(pttTrack){pttTrack.enabled=false;els.ptt.classList.remove("active");els.ptt.textContent="🎙️ 押している間だけ話す";}}

  async function runDiagnostics(){
    const lines=[];
    const ok=(v)=>v?"OK":"NG";
    lines.push("HTTPS/安全な接続: "+ok(window.isSecureContext));
    lines.push("WebRTC API: "+ok(typeof RTCPeerConnection!=="undefined"));
    lines.push("カメラ・マイクAPI: "+ok(!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia)));
    const connection=navigator.connection||navigator.mozConnection||navigator.webkitConnection;
    lines.push("ネットワーク方式: "+(connection?(connection.effectiveType||connection.type||"取得可能"):"ブラウザ非対応"));
    if(!validGas()) lines.push("GAS URL: 未設定または形式不正");
    else {
      const health=await new Promise(resolve=>{
        const callback="webrtcHealthCallback";
        const script=document.createElement("script");
        const timer=setTimeout(()=>{cleanup();resolve(false);},8000);
        function cleanup(){clearTimeout(timer);script.remove();try{delete window[callback];}catch(_){}}
        window[callback]=(data)=>{cleanup();resolve(!!(data&&data.ok));};
        const url=new URL(normalizeGasUrl());url.searchParams.set("action","health");url.searchParams.set("callback",callback);url.searchParams.set("_",String(Date.now()));
        script.src=url.toString();script.onerror=()=>{cleanup();resolve(false);};document.head.appendChild(script);
      });
      lines.push("GAS応答: "+ok(health));
    }
    if(typeof RTCPeerConnection!=="undefined"){
      let testPc=null;
      try{
        const candidates=[];
        testPc=new RTCPeerConnection(ICE);
        testPc.createDataChannel("connectivity-test");
        testPc.onicecandidate=(event)=>{if(event.candidate)candidates.push(event.candidate.candidate);};
        const offer=await testPc.createOffer();await testPc.setLocalDescription(offer);
        await new Promise(resolve=>{
          if(testPc.iceGatheringState==="complete"){resolve();return;}
          const timer=setTimeout(resolve,5000);
          testPc.onicegatheringstatechange=()=>{if(testPc.iceGatheringState==="complete"){clearTimeout(timer);resolve();}};
        });
        const srflx=candidates.some(x=>x.includes(" typ srflx "));
        const host=candidates.some(x=>x.includes(" typ host "));
        lines.push("STUN外部経路候補: "+(srflx?"確認できました": "確認できません（制限・タイムアウトの可能性）"));
        lines.push("ローカル候補: "+ok(host));
      }catch(error){lines.push("ICE診断: NG ("+error.message+")");}
      finally{if(testPc)testPc.close();}
    }
    els.diagnosticResult.textContent=lines.join("\n");
  }

  els.runDiagnostics.addEventListener("click",()=>{els.runDiagnostics.disabled=true;els.diagnosticResult.textContent="診断中…";runDiagnostics().catch(e=>{els.diagnosticResult.textContent="診断エラー: "+e.message;}).finally(()=>{els.runDiagnostics.disabled=false;});});
  els.refreshDeviceStatus.addEventListener("click",()=>fetchRegisteredDeviceStatus().catch(error=>{els.registeredDeviceStatus.textContent="状態確認失敗: "+error.message;activePresence=null;updateDeviceActions();}));
  els.connectRegisteredDevice.addEventListener("click",async()=>{
    try{
      const presence=await fetchRegisteredDeviceStatus();
      if(!presence.online||!presence.room)throw new Error("Androidが待機中ではありません。AndroidアプリでWebRTCを開始してください。");
      els.joinCode.value=String(presence.room);
      setRole("ipad");
      await joinAndroid();
    }catch(error){els.deviceManagerStatus.textContent="接続を開始できません: "+error.message;}
  });
  els.registerDevice.addEventListener("click",()=>registerPairedDevice().catch(error=>{els.deviceManagerStatus.textContent="端末登録に失敗しました: "+error.message;}).finally(()=>{els.registerDevice.disabled=false;}));
  els.registeredDeviceSelect.addEventListener("change",()=>{
    const selected=registeredDevices.find(d=>d.deviceId===els.registeredDeviceSelect.value)||null;
    setActiveDevice(selected);
    if(selected)els.deviceManagerStatus.textContent="選択中："+(selected.nickname||selected.label)+(selected.lastConnected?" / 最終接続："+new Date(selected.lastConnected).toLocaleString():" / 接続履歴なし")+" / 履歴 "+((selected.history||[]).length)+"件";
  });
  els.renameDevice.addEventListener("click",()=>{
    if(!activeDevice)return;
    const name=prompt("端末の表示名を入力してください。",activeDevice.nickname||activeDevice.label);
    if(name===null)return;
    const clean=name.trim().slice(0,40);if(!clean){els.deviceManagerStatus.textContent="名前は1文字以上にしてください。";return;}
    registeredDevices=registeredDevices.map(d=>d.deviceId===activeDevice.deviceId?{...d,nickname:clean}:d);
    saveRegisteredDevices();renderRegisteredDevices();els.deviceManagerStatus.textContent="端末名を変更しました。";
  });
  els.favoriteDevice.addEventListener("click",()=>{
    if(!activeDevice)return;
    registeredDevices=registeredDevices.map(d=>d.deviceId===activeDevice.deviceId?{...d,favorite:!d.favorite}:d);
    saveRegisteredDevices();renderRegisteredDevices();
  });
  els.viewDeviceHistory.addEventListener("click",()=>{
    if(!activeDevice)return;
    const history=Array.isArray(activeDevice.history)?activeDevice.history:[];
    const lines=history.slice(0,20).map((item,index)=>(index+1)+". "+new Date(item.time).toLocaleString());
    alert(lines.length?"接続履歴（最新20件）\n"+lines.join("\n"):"この端末の接続履歴はありません。");
  });
  els.removeDevice.addEventListener("click",async()=>{
    if(!activeDevice||!confirm("このAndroid端末の登録を解除しますか？このiPad以外に保存された同じ登録情報も無効になります。"))return;
    const target=activeDevice;els.removeDevice.disabled=true;
    try{
      if(role==="ipad"&&started&&room&&authDeviceId===target.deviceId){
        try{await sendSignal("hangup",{reason:"device-unpaired"},"android");}catch(_){}
        await stop({notify:false,reason:"登録端末を解除しました。"});
      }
      await revokeRegisteredDevice(target);
      registeredDevices=registeredDevices.filter(d=>d.deviceId!==target.deviceId);
      saveRegisteredDevices();authDeviceId="";authToken="";activeDevice=null;activePresence=null;renderRegisteredDevices();
      els.deviceManagerStatus.textContent="登録を解除し、サーバー側の認証トークンも失効させました。";
    }catch(error){els.deviceManagerStatus.textContent="登録解除に失敗しました: "+error.message;updateDeviceActions();}
  });
  els.saveGas.addEventListener("click",()=>{
    if(!validGas()){els.gasStatus.textContent="URLを確認してください";log("GASの「ウェブアプリ URL」を使ってください。例：https://script.google.com/macros/s/.../exec（末尾は /exec）。URLの前後の空白や引用符は自動で除去します。");return;}
    const normalized = normalizeGasUrl();
    if (!normalized) { els.gasStatus.textContent="URLを確認してください"; log("GASの「ウェブアプリ URL」をコピーしてください。末尾が /exec のURLが必要です。"); return; }
    els.gasUrl.value = normalized;
    localStorage.setItem("webrtcGasUrl",normalized);els.gasStatus.textContent="保存済み ✓";log("GAS URLをこの端末に保存しました。");
  });
  els.gasUrl.value=localStorage.getItem("webrtcGasUrl")||"";if(els.gasUrl.value)els.gasStatus.textContent="保存済み";
  renderRegisteredDevices();
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
    locationTimeout=setTimeout(()=>{locationTimeout=null;els.locationStatus.textContent="応答がありません。Androidの接続・位置情報許可を確認してください。";els.requestLocation.disabled=false;},35000);
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
    if(role==="ipad"){
      if(!started||!pc||pc.connectionState!=="connected"){log("Androidとの接続後にカメラを切り替えられます。");return;}
      els.cameraSwitch.disabled=true;
      try{await sendSignal("camera_switch",{requestedAt:Date.now()},"android");log("Androidへカメラ切り替えを要求しました。");}
      catch(error){log("カメラ切り替え要求に失敗しました: "+error.message);}
      setTimeout(()=>{if(started)els.cameraSwitch.disabled=false;},1800);
      return;
    }
    if(!localStream||!pc||!started)return;
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