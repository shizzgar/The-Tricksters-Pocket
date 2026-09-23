'use strict';
// Original, minimal probe: no hooks, no memory dump, no Java dependency.
setImmediate(() => {
  send({kind: 'native-ready', pid: Process.id, arch: Process.arch,
    pageSize: Process.pageSize, pointerSize: Process.pointerSize,
    runtime: Script.runtime, frida: Frida.version,
    moduleCount: Process.enumerateModules().length,
    capabilities: {
      moduleObserver: typeof Process.attachModuleObserver === 'function',
      globalExportLookup: typeof Module.findGlobalExportByName === 'function'
    }});
});
