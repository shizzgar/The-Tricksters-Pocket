Rebro.module('native_probe', false, function (o, c) {
  c.emit('environment', {frida:Frida.version,arch:Process.arch,platform:Process.platform,pointer_size:Process.pointerSize,page_size:Process.pageSize,pid:Process.id,runtime:Script.runtime,module_observer:typeof Process.attachModuleObserver==='function'});
});
