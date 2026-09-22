'use strict';
// Copy to case; adapt class, method and EXACT overload; pinned bridge required.
// No values logged, and the original implementation's result is preserved.
setImmediate(() => {
  if (typeof Java === 'undefined' || !Java.available) {
    send({kind: 'probe-error', error: 'Pinned Java bridge required'});
    return;
  }
  Java.perform(() => {
    try {
      const Target = Java.use('com.example.lab.Worker');
      const method = Target.process.overload('java.lang.String');
      let calls = 0;
      method.implementation = function (value) {
        calls++;
        if (calls <= 10 || calls % 100 === 0) {
          send({kind: 'java-call', count: calls, thread: Process.getCurrentThreadId()});
        }
        return method.call(this, value);
      };
      send({kind: 'java-hook-installed'});
      rpc.exports = {dispose() { Java.perform(() => { method.implementation = null; }); }};
    } catch (e) {
      send({kind: 'probe-error', error: String(e)});
    }
  });
});
