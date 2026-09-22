'use strict';
// Requires Java supplied in THIS script by the reviewed pinned bridge loader.
// Loading a bridge as an unrelated script does not share its JS globals.
setImmediate(() => {
  try {
    if (typeof Java === 'undefined' || !Java.available) {
      throw new Error('Java unavailable: verify bridge loading and target runtime');
    }
    Java.perform(() => {
      try {
        const Clock = Java.use('android.os.SystemClock');
        send({kind: 'java-ready', pid: Process.id, uptimeMillis: Clock.uptimeMillis().toString()});
      } catch (e) {
        send({kind: 'probe-error', error: String(e)});
      }
    });
  } catch (e) {
    send({kind: 'probe-error', error: String(e)});
  }
});
