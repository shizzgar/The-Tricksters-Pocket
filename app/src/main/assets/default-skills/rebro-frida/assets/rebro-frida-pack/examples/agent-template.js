/* Copy into agents/<id>.js; also register metadata in catalog.json and options.json. */
Rebro.module('my_agent', true, function (o, c) {
    'use strict';
    c.hookJava(o.class_name, o.method, function (args, state) {
        state.start = Date.now();
        c.emit('call', {args: args.map(c.value)});
    }, function (args, result, state) {
        c.emit('result', {result: c.value(result), elapsed_ms: Date.now() - state.start});
    }, o.signature);
});

