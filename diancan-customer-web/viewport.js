/** Customer-only viewport and gesture policy. Single-finger scrolling and rapid button taps remain native. */
export function installCustomerViewport(win = window, doc = document) {
  const html = doc.documentElement;
  const viewport = doc.querySelector('meta[name="viewport"]');
  const originalViewport = viewport?.getAttribute('content');
  const properties = ['--customer-height','--customer-width','--customer-left','--customer-top'];
  const originalProperties = properties.map(name => html.style.getPropertyValue(name));
  html.classList.add('customer-mode');
  viewport?.setAttribute('content','width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover');
  let frame = 0, lastTap = null, touchStart = null, moved = false, multi = false;
  const sync = () => {
    frame = 0;
    const v = win.visualViewport, width = v?.width || win.innerWidth;
    html.style.setProperty('--customer-height', `${v?.height || win.innerHeight}px`);
    html.style.setProperty('--customer-width', `${Math.min(width,1180)}px`);
    html.style.setProperty('--customer-left', `${(v?.offsetLeft || 0) + Math.max(0,(width-1180)/2)}px`);
    html.style.setProperty('--customer-top', `${v?.offsetTop || 0}px`);
  };
  const schedule = () => { if (!frame) frame = win.requestAnimationFrame(sync); };
  const preventGesture = event => { if (event.cancelable) event.preventDefault(); };
  const onStart = event => {
    if (event.touches.length > 1) { multi = true; preventGesture(event); return; }
    const t = event.touches[0]; touchStart = t ? { x:t.clientX,y:t.clientY } : null; moved = false;
  };
  const onMove = event => {
    if (event.touches.length > 1) { multi = true; preventGesture(event); return; }
    const t = event.touches[0];
    if (t && touchStart && Math.hypot(t.clientX-touchStart.x,t.clientY-touchStart.y)>10) moved = true;
  };
  const onEnd = event => {
    if (event.touches.length) return;
    if (multi || moved) { multi = false; lastTap = null; return; }
    const target = event.target?.closest ? event.target : event.target?.parentElement;
    if (target?.closest('button,a,input,textarea,select,summary,[role="button"]')) { lastTap = null; return; }
    const t = event.changedTouches[0]; if (!t) return;
    const now = win.performance.now();
    if (lastTap && now-lastTap.time<300 && Math.hypot(t.clientX-lastTap.x,t.clientY-lastTap.y)<24) {
      preventGesture(event); lastTap = null;
    } else lastTap = {time:now,x:t.clientX,y:t.clientY};
  };
  const resetTouch = () => { lastTap = null; touchStart = null; moved = false; multi = false; };
  const listeners = [['touchstart',onStart],['touchmove',onMove],['touchend',onEnd],['touchcancel',resetTouch],['gesturestart',preventGesture],['gesturechange',preventGesture]];
  listeners.forEach(([type,handler]) => doc.addEventListener(type,handler,{passive:false,capture:true}));
  win.addEventListener('resize',schedule); win.addEventListener('orientationchange',schedule);
  win.visualViewport?.addEventListener('resize',schedule); win.visualViewport?.addEventListener('scroll',schedule);
  sync();
  return () => {
    if (frame) win.cancelAnimationFrame(frame);
    listeners.forEach(([type,handler]) => doc.removeEventListener(type,handler,true));
    win.removeEventListener('resize',schedule); win.removeEventListener('orientationchange',schedule);
    win.visualViewport?.removeEventListener('resize',schedule); win.visualViewport?.removeEventListener('scroll',schedule);
    html.classList.remove('customer-mode');
    if (viewport && originalViewport != null) viewport.setAttribute('content',originalViewport);
    properties.forEach((name,index) => originalProperties[index] ? html.style.setProperty(name,originalProperties[index]) : html.style.removeProperty(name));
  };
}
