'use strict';
// Copy to the case, select ONE module/export, then review and load.
const targetModule = 'libexample.so';
const targetExport = 'example_function';
let listener = null;
let calls = 0;
let reported = 0;
function attachIfReady(module) {
  if (module.name !== targetModule || listener !== null) return;
  const address = module.findExportByName(targetExport);
  if (address === null) {
    send({kind: 'probe-error', error: 'Selected export absent'});
    return;
  }
  listener = Interceptor.attach(address, {onEnter() { calls++; }});
  send({kind: 'hook-installed', module: module.name, address: address.toString()});
}
if (typeof Process.attachModuleObserver !== 'function') {
  send({kind: 'probe-error', error: 'Module observer unsupported; use an explicitly timed fallback'});
} else {
  const observer = Process.attachModuleObserver({
    onAdded(module) { attachIfReady(module); },
    onRemoved(module) {
      if (module.name === targetModule && listener !== null) {
        listener.detach(); listener = null;
      }
    }
  });
  const timer = setInterval(() => {
    if (calls !== reported) {
      send({kind: 'call-count', total: calls, delta: calls - reported});
      reported = calls;
    }
  }, 1000);
  rpc.exports = {dispose() {
    clearInterval(timer);
    observer.detach();
    if (listener !== null) listener.detach();
    listener = null;
  }};
}
