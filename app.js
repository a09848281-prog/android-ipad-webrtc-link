(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const els = {
    gasUrl:$("gasUrl"),gasStatus:$("gasStatus"),saveGas:$("saveGas"),hostMode:$("hostMode"),viewerMode:$("viewerMode"),
    hostPanel:$("hostPanel"),viewerPanel:$("viewerPanel"),startHost:$("startHost"),roomCode:$("roomCode"),copyCode:$("copyCode"),
    joinCode:$("joinCode"),joinRoom:$("joinRoom"),remoteVideo:$("remoteVideo"),localVideo:$("localVideo"),
    placeholder:$("videoPlaceholder"),connectionState:$("connectionState"),mediaState:$("mediaState"),
    ptt:$("pttButton"),disconnect:$("disconnect"),log:$("log")
  };
  const ICE = {iceServers:[{urls:["stun:stun.l.google.com:19302","stun:stun1.l.google.com:19302"]}]};
  let role = "android", room = "", pc = null, localStream = null, pttTrack = null;
  let pollTimer = null, seen = new Set(), pendingCandidates = [], remoteDescriptionSet = false;
  let started = false, polling = false;

  function log(message){ els.log.textContent = message; }
  function setState(text, kind=""){ els.connectionState.textContent=text; els.connectionState.className="state "+kind; }
  function gas(){ return els.gasUrl.value.trim(); }
  function validGas(){ try { const u=new URL(gas()); return u.protocol==="https:" && /script\.google\.com$/.test(u.hostname) && /\/exec$/.test(u.pathname); } catch(_){return false;} }
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
  function setupPeer(){
    pc=peerConfig();
    pc.onicecandidate=(event)=>{if(event.candidate) sendSignal("candidate",event.candidate.toJSON(),role==="android"?"ipad":"android").catch(()=>{});};
    pc.onconnectionstatechange=()=>{
      const s=pc.connectionState;
      if(s==="connected"){setState("接続中","ok");log("端末同士が接続されました。");}
      else if(s==="connecting"){setState("接続処理中","wait");}
      else if(s==="failed"){setState("接続失敗");log("P2P接続に失敗しました。別のWi-Fiやモバイル回線ではTURNサーバーが必要になる場合があります。");}
      else if(s==="disconnected"){setState("切断");}
      else if(s==="closed"){setState("未接続");}
    };
    pc.ontrack=(event)=>{
      let stream=event.streams && event.streams[0];
      if(!stream){stream=new MediaStream([event.track]);}
      els.remoteVideo.srcObject=stream; els.placeholder.classList.add("hidden");
      els.mediaState.textContent="映像・音声を受信中";
      els.remoteVideo.play().catch(()=>{log("映像は受信しています。画面をタップして再生してください。");});
    };
    pc.oniceconnectionstatechange=()=>{if(pc.iceConnectionState==="checking")setState("経路確認中","wait");};
  }
  async function getMedia(constraints){
    if(!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error("このブラウザではカメラ・マイクを利用できません。HTTPSのページで開いてください。");
    return navigator.mediaDevices.getUserMedia(constraints);
  }
  async function startAndroid(){
    if(!validGas()) throw new Error("先に正しいGAS WebアプリURL（/exec）を保存してください。");
    els.startHost.disabled=true; log("Androidのカメラ・マイクを起動しています…");
    localStream=await getMedia({audio:{echoCancellation:true,noiseSuppression:true},video:{facingMode:"environment",width:{ideal:1280},height:{ideal:720}}});
    els.localVideo.srcObject=localStream; room=makeRoom(); els.roomCode.textContent=room;els.copyCode.disabled=false;
    setupPeer(); localStream.getTracks().forEach(t=>pc.addTrack(t,localStream));
    started=true; els.disconnect.disabled=false; setState("接続待機中","wait");startPolling();
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
    started=true;els.disconnect.disabled=false;els.ptt.disabled=false;setState("Androidを検索中","wait");startPolling();
    log("Android側からの接続情報を待っています…");
  }
  async function stop(){
    started=false;stopPolling();
    if(pc){pc.ontrack=null;pc.onicecandidate=null;pc.close();pc=null;}
    if(localStream){localStream.getTracks().forEach(t=>t.stop());localStream=null;}
    pttTrack=null;pendingCandidates=[];remoteDescriptionSet=false;seen.clear();
    els.remoteVideo.srcObject=null;els.localVideo.srcObject=null;els.placeholder.classList.remove("hidden");
    els.mediaState.textContent="映像・音声：未受信";els.ptt.disabled=true;els.ptt.classList.remove("active");els.disconnect.disabled=true;
    els.startHost.disabled=false;els.joinRoom.disabled=false;els.roomCode.textContent="------";els.copyCode.disabled=true;
    setState("未接続");log("切断しました。");
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
  els.ptt.addEventListener("pointerdown",pttDown);["pointerup","pointercancel","pointerleave","lostpointercapture"].forEach(ev=>els.ptt.addEventListener(ev,pttUp));
  document.addEventListener("visibilitychange",()=>{if(document.hidden && pttTrack)pttUp();});
  if ("serviceWorker" in navigator && location.protocol === "https:") {
    window.addEventListener("load", () => navigator.serviceWorker.register("./sw.js").catch(err => console.warn("PWA worker registration failed", err)));
  }
  window.addEventListener("beforeunload",()=>{stopPolling();if(localStream)localStream.getTracks().forEach(t=>t.stop());});
})();