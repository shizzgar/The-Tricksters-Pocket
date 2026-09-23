/* Original Rebro Pack runtime. Frida 17 API. MIT. */
(function () {
'use strict';
const config = globalThis.REBRO_CONFIG;
const limits = config.limits;
const registry = new Map(), cleanups = [], statuses = {};
const javaKeys = new Set();
let stopped = false, events = 0, bytes = 0, hooks = 0, windowStart = Date.now(), windowEvents = 0, dropped = 0;
function control(kind, data, agent) {
    send({schema: 1, time: Date.now(), pid: Process.id, agent: agent || 'runtime', kind, data});
}
function emit(agent, kind, data) {
    if (stopped) return;
    const now = Date.now();
    if (now - windowStart >= 1000) { windowStart = now; windowEvents = 0; }
    if (++windowEvents > limits.max_per_second) { dropped++; return; }
    const record = {schema: 1, time: now, pid: Process.id, tid: Process.getCurrentThreadId(), agent, kind, data};
    let size;
    try { size = JSON.stringify(record).length * 3; } catch (_) { return; }
    if (events >= limits.max_events || bytes + size > limits.max_bytes) {
        control('quota', {events, bytes, dropped});
        stop();
        return;
    }
    events++; bytes += size;
    send(record);
}
function clip(value, max) {
    if (value === null || value === undefined) return null;
    const s = String(value);
    return s.length > (max || limits.max_string) ? s.slice(0, max || limits.max_string) + '…' : s;
}
function value(v) {
    if (v === null || v === undefined) return null;
    if (typeof v === 'string') return limits.capture_strings ? clip(v) : {type: 'string', length: v.length};
    if (typeof v === 'number' || typeof v === 'boolean') return v;
    if (v.$className) return {class: v.$className};
    return {type: typeof v};
}
function regex(pattern, fallback) { return new RegExp(pattern === undefined ? fallback : pattern); }
function rangeEnd(range) { return range.base.add(range.size); }
function cstring(p) {
    try {
        if (p.isNull()) return null;
        const r = Process.findRangeByAddress(p);
        if (!r || r.protection[0] !== 'r') return null;
        const remaining = rangeEnd(r).sub(p).toUInt32();
        return p.readCString(Math.min(limits.max_string, remaining));
    } catch (_) { return null; }
}
function status(id, state, details) {
    statuses[id] = {status: state, ...details};
    control('agent_status', statuses[id], id);
}
function stop() {
    if (stopped) return {stopped: true, events, bytes, dropped};
    stopped = true;
    const performCleanup = () => {
        for (const f of cleanups.splice(0).reverse()) {
            try { f(); } catch (e) { control('cleanup_error', {error: String(e)}); }
        }
    };
    if (globalThis.Java && Java.available && typeof Java.performNow === 'function') {
        try { Java.performNow(performCleanup); } catch (_) { performCleanup(); }
    } else performCleanup();
    return {stopped: true, events, bytes, dropped, hooks};
}
function context(id) {
    const ctx = {
        id, limits,
        emit: (kind, data) => emit(id, kind, data),
        clip, value, regex, cstring,
        require(condition, message) { if (!condition) throw new Error(message); },
        addCleanup(fn) { cleanups.push(fn); },
        list(items) { return items.slice(0, limits.max_items); },
        backtrace(cpu) {
            try { return Thread.backtrace(cpu, Backtracer.ACCURATE).slice(0, 12).map(p => DebugSymbol.fromAddress(p).toString()); }
            catch (e) { return ['unavailable: ' + String(e)]; }
        },
        attach(address, callbacks) {
            if (stopped) return;
            if (++hooks > limits.max_hooks) { hooks--; throw new Error('max_hooks reached'); }
            try {
                const listener = Interceptor.attach(address, callbacks);
                cleanups.push(() => listener.detach());
                ctx.emit('hook_installed', {address: String(address)});
                return listener;
            } catch (e) { hooks--; throw e; }
        },
        onModule(pattern, fn) {
            const rx = regex(pattern, '^libc\\.so$');
            if (typeof Process.attachModuleObserver !== 'function') {
                throw new Error('Process.attachModuleObserver unavailable in this agent runtime');
            }
            const observer = Process.attachModuleObserver({
                onAdded(m) {
                    if (stopped || !rx.test(m.name)) return;
                    try { fn(m); } catch (e) { ctx.emit('hook_error', {module: m.name, error: String(e)}); }
                }
            });
            cleanups.push(() => observer.detach());
            return observer;
        },
        hookJava(className, method, before, after, signature, factory) {
            const klass = (factory || Java).use(className);
            if (!klass[method] || !klass[method].overloads) throw new Error(className + '.' + method + ' unavailable');
            let count = 0;
            for (const ov of klass[method].overloads) {
                const types = ov.argumentTypes.map(t => t.className);
                if (signature && JSON.stringify(types) !== JSON.stringify(signature)) continue;
                const key = className + '.' + method + '(' + types.join(',') + ')';
                if (javaKeys.has(key)) throw new Error('Duplicate Java hook in this profile: ' + key);
                // The getter may expose an opaque NativeCallback, not the assigned JS function.
                // Do not take ownership of an existing replacement we cannot restore faithfully.
                if (ov.implementation !== null) throw new Error('Java method already has an implementation: ' + key);
                if (++hooks > limits.max_hooks) { hooks--; throw new Error('max_hooks reached'); }
                const handler = function () {
                    const args = Array.prototype.slice.call(arguments);
                    const state = {};
                    if (!stopped && before) {
                        try { before.call(this, args, state, types); }
                        catch (e) { ctx.emit('observer_error', {method: key, error: String(e)}); }
                    }
                    let result;
                    try { result = ov.apply(this, args); }
                    catch (e) {
                        ctx.emit('java_throw', {method: key, class: e.$className || null});
                        throw e; // Preserve original application semantics.
                    }
                    if (!stopped && after) {
                        try { after.call(this, args, result, state, types); }
                        catch (e) { ctx.emit('observer_error', {method: key, error: String(e)}); }
                    }
                    return result;
                };
                try { ov.implementation = handler; }
                catch (e) { hooks--; throw e; }
                const installed = ov.implementation;
                javaKeys.add(key); count++;
                cleanups.push(() => {
                    // Keep another in-context owner's later replacement intact.
                    if (ov.implementation === installed) ov.implementation = null;
                    javaKeys.delete(key);
                });
            }
            if (!count) throw new Error('No matching overload: ' + className + '.' + method);
            ctx.emit('java_hooks', {class: className, method, count});
            return count;
        },
        tryJava(className, method, before, after, signature) {
            try { return ctx.hookJava(className, method, before, after, signature); }
            catch (e) { ctx.emit('unavailable', {class: className, method, error: String(e)}); return 0; }
        },
        byteLength(obj) {
            if (obj === null || obj === undefined) return 0;
            try { return typeof obj.length === 'number' ? obj.length : null; } catch (_) { return null; }
        },
        url(raw) { return clip(String(raw).split(/[?#]/)[0]); },
        resolveTarget(m, opts) {
            if (opts.symbol) {
                const p = m.findExportByName(opts.symbol);
                if (!p) throw new Error('Export not found: ' + opts.symbol);
                return p;
            }
            if (!/^0x[0-9a-f]+$/i.test(String(opts.offset || ''))) throw new Error('offset must be hexadecimal, or specify symbol');
            const n = Number(opts.offset);
            if (!Number.isSafeInteger(n) || n < 0 || n >= m.size) throw new Error('offset is outside the module');
            return m.base.add(n);
        }
    };
    return ctx;
}
globalThis.Rebro = {
    module(id, java, init) { registry.set(id, {java, init}); },
    context, stop,
    start() {
        let remaining = config.agents.length;
        const done = () => { if (--remaining === 0 && !stopped) control('ready', {statuses}); };
        for (const id of config.agents) {
            const item = registry.get(id);
            if (!item) { status(id, 'failed', {error: 'Not registered'}); done(); continue; }
            const launch = () => {
                if (stopped) { done(); return; }
                try { item.init(config.options[id] || {}, context(id)); status(id, 'active', {meaning: 'initialized; check hook/hit events for coverage'}); }
                catch (e) { status(id, 'failed', {error: String(e), stack: String(e.stack || '')}); }
                done();
            };
            if (item.java) {
                if (!globalThis.Java || !Java.available) { status(id, 'failed', {error: 'Java unavailable in this process/bridge'}); done(); }
                else {
                    try { Java.perform(launch); } catch (e) { status(id, 'failed', {error: String(e)}); done(); }
                }
            } else launch();
        }
    }
};
rpc.exports = {stop, status() { return {statuses, events, bytes, hooks, dropped, stopped}; }};
})();
