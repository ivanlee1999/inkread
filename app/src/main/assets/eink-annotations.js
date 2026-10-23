/* Body-only annotation anchors. Never wraps, splits, or edits article text nodes. */
(function () {
    'use strict';
    var VERSION = 1, CONTEXT = 32, CAPTURE_BYTE_LIMIT = 128 * 1024, frame, map, enabled = false, destroyed = false;
    var annotations = [], locations = [], ranges = [], attached = [], redrawFrame = 0, overlay, observer;
    var resizeSession = null, autoEnabled = false, autoGesture = null, autoFrame = 0;
    var selectionRevision = 0, autoConsumed = false, sessionEmittedRanges = [], lastAutoEmittedKey = null;
    var generation = window._inkreadGeneration;
    var OMIT = /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE)$/;
    function notify(name, value) {
        try { if (window.Android && typeof Android[name] === 'function') Android[name](generation, value); } catch (ignored) {}
    }
    function live(expectedGeneration) {
        return !destroyed && generation === expectedGeneration && window._inkreadGeneration === generation;
    }
    function isInteger(value) { return typeof value === 'number' && isFinite(value) && Math.floor(value) === value; }
    function scalarBoundary(text, offset) {
        return isInteger(offset) && offset >= 0 && offset <= text.length &&
            !(offset > 0 && offset < text.length && /[\uD800-\uDBFF]/.test(text.charAt(offset - 1)) && /[\uDC00-\uDFFF]/.test(text.charAt(offset)));
    }
    function fingerprint(text, nodes) {
        // Two independent 32-bit accumulators plus length; quote/endpoints are also
        // validated before accepting the fast path. This is identity, not security.
        var a = 2166136261, b = 5381;
        var value = text + '\u0000' + nodes.map(function (n) { return n.data.length; }).join(',');
        for (var i = 0; i < value.length; i++) { a = Math.imul(a ^ value.charCodeAt(i), 16777619); b = Math.imul(b, 33) ^ value.charCodeAt(i); }
        return 'body-v1-' + text.length + '-' + (a >>> 0).toString(16) + '-' + (b >>> 0).toString(16);
    }
    function buildMap(root) {
        // v1 is the exact DOM Range.toString text model: concatenate original
        // body text nodes, including whitespace-only nodes, without normalization,
        // trimming or invented paragraph/BR separators. Selection.toString may
        // insert layout-dependent newlines; those are deliberately not this map.
        var nodes = [], segments = [], parts = [], length = 0;
        function visit(node) {
            if (node.nodeType !== 3) {
                if (node !== root && (OMIT.test(node.tagName) || (node.classList && node.classList.contains('eink-metadata')))) return;
                for (var child = node.firstChild; child; child = child.nextSibling) visit(child);
                return;
            }
            var index = nodes.length; nodes.push(node);
            parts.push(node.data);
            segments.push({ start: length, end: length + node.data.length, node: index, offset: 0 });
            length += node.data.length;
        }
        visit(root);
        var text = parts.join('');
        return { text: text, nodes: nodes, segments: segments, fingerprint: fingerprint(text, nodes) };
    }
    function ensureMap() {
        if (!map && frame) map = buildMap(frame);
        return !!map;
    }
    function pointAt(offset, end) {
        if (!map || !scalarBoundary(map.text, offset)) return null;
        var segments = map.segments;
        // At node joins, choose the following node for starts and preceding
        // node for ends. The represented exact text range remains identical.
        if (end) {
            for (var i = segments.length - 1; i >= 0; i--) {
                var s = segments[i];
                if (s.node !== null && offset > s.start) return { node: s.node, offset: s.offset + Math.min(offset - s.start, s.end - s.start) };
            }
        } else {
            for (var j = 0; j < segments.length; j++) {
                var t = segments[j];
                if (t.node !== null && offset < t.end) return { node: t.node, offset: t.offset + Math.max(0, offset - t.start) };
            }
        }
        return null;
    }
    function offsetAt(node, offset, end) {
        var index = map.nodes.indexOf(node);
        if (index < 0 || !scalarBoundary(node.data, offset)) return null;
        var best = null;
        for (var i = 0; i < map.segments.length; i++) {
            var s = map.segments[i];
            if (s.node === null) continue;
            var stop = s.offset + s.end - s.start;
            if (s.node === index && offset >= s.offset && offset <= stop) return s.start + offset - s.offset;
            if (!end && (s.node > index || (s.node === index && offset < s.offset))) return s.start;
            if (end && (s.node < index || (s.node === index && offset > stop))) best = s.end;
        }
        return best;
    }
    function contextMatches(anchor, start, end) {
        return typeof anchor.prefix === 'string' && typeof anchor.suffix === 'string' &&
            map.text.slice(Math.max(0, start - anchor.prefix.length), start) === anchor.prefix &&
            map.text.slice(end, end + anchor.suffix.length) === anchor.suffix;
    }
    function validEndpoints(anchor) {
        var a = map.nodes[anchor.startNode], b = map.nodes[anchor.endNode];
        return isInteger(anchor.startNode) && isInteger(anchor.endNode) && a && b && scalarBoundary(a.data, anchor.startOffset) && scalarBoundary(b.data, anchor.endOffset) &&
            offsetAt(a, anchor.startOffset, false) === anchor.start && offsetAt(b, anchor.endOffset, true) === anchor.end;
    }
    function locate(anchor) {
        if (!map || !anchor || anchor.version !== VERSION || anchor.textMapVersion !== VERSION ||
            typeof anchor.quote !== 'string' || !anchor.quote.trim()) return null;
        // User-visible/export text is the browser's Selection.toString(), whose
        // layout paragraph separators can differ from raw Range.toString().
        var rangeQuote = typeof anchor.rangeQuote === 'string' ? anchor.rangeQuote : anchor.quote;
        if (!rangeQuote.trim()) return null;
        if (anchor.documentFingerprint === map.fingerprint && scalarBoundary(map.text, anchor.start) &&
            scalarBoundary(map.text, anchor.end) && anchor.end > anchor.start &&
            map.text.slice(anchor.start, anchor.end) === rangeQuote && contextMatches(anchor, anchor.start, anchor.end) && validEndpoints(anchor)) {
            return { start: anchor.start, end: anchor.end };
        }
        var found = null, from = 0;
        while (from <= map.text.length) {
            var start = map.text.indexOf(rangeQuote, from);
            if (start < 0) break;
            var end = start + rangeQuote.length;
            if (scalarBoundary(map.text, start) && scalarBoundary(map.text, end) && contextMatches(anchor, start, end)) {
                if (found) return null; // Ambiguous anchors must remain in the list, not guess.
                found = { start: start, end: end };
            }
            from = start + 1;
        }
        return found;
    }
    function makeRange(start, end) {
        var a = pointAt(start, false), b = pointAt(end, true);
        if (!a || !b) return null;
        var range = document.createRange();
        range.setStart(map.nodes[a.node], a.offset); range.setEnd(map.nodes[b.node], b.offset);
        return range;
    }
    function encodeCapture(value) {
        var json = JSON.stringify(value), bytes = 0;
        // Count the serialized UTF-8 payload, not UTF-16 length. JSON escaping
        // can expand controls, while CJK and emoji take multiple UTF-8 bytes.
        // Reject atomically: truncating would corrupt the exact quote/anchor.
        for (var i = 0; i < json.length; i++) {
            var code = json.charCodeAt(i);
            if (code < 0x80) bytes++;
            else if (code < 0x800) bytes += 2;
            else if (code >= 0xD800 && code <= 0xDBFF && i + 1 < json.length &&
                json.charCodeAt(i + 1) >= 0xDC00 && json.charCodeAt(i + 1) <= 0xDFFF) { bytes += 4; i++; }
            else bytes += 3;
            if (bytes > CAPTURE_BYTE_LIMIT) return '{"error":"selection_too_large"}';
        }
        return json;
    }
    function captureSelection(expectedGeneration) {
        if (!live(expectedGeneration) || !enabled) return null;
        var selection = window.getSelection();
        if (!selection || selection.rangeCount !== 1 || selection.isCollapsed || !ensureMap()) return null;
        // Range endpoints are document ordered even for right-to-left drags.
        var range = selection.getRangeAt(0);
        var startNode = range.startContainer, endNode = range.endContainer;
        var startOffset = range.startOffset, endOffset = range.endOffset;
        // Element endpoints (Select all, keyboard selection) are resolved through
        // intersecting body text only. Metadata never becomes an article quote.
        if (map.nodes.indexOf(startNode) < 0 || map.nodes.indexOf(endNode) < 0) {
            var included = map.nodes.filter(function (n) { return n.length && range.intersectsNode(n); });
            if (!included.length) return null;
            if (map.nodes.indexOf(startNode) < 0) { startNode = included[0]; startOffset = 0; }
            if (map.nodes.indexOf(endNode) < 0) { endNode = included[included.length - 1]; endOffset = endNode.length; }
        }
        var start = offsetAt(startNode, startOffset, false), end = offsetAt(endNode, endOffset, true);
        if (start === null || end === null || end <= start) return null;
        if (end <= start || !map.text.slice(start, end).trim() || !scalarBoundary(map.text, start) || !scalarBoundary(map.text, end)) return null;
        var rawQuote = map.text.slice(start, end);
        // Do not silently clip a selection spanning metadata/omitted content and
        // then export its original, wider display text under a body-only anchor.
        if (range.toString() !== rawQuote) return null;
        var a = pointAt(start, false), b = pointAt(end, true);
        return encodeCapture({ version: VERSION, textMapVersion: VERSION, documentFingerprint: map.fingerprint,
            quote: selection.toString(), rangeQuote: rawQuote, start: start, end: end,
            prefix: map.text.slice(Math.max(0, start - CONTEXT), start), suffix: map.text.slice(end, end + CONTEXT),
            startNode: a.node, startOffset: a.offset, endNode: b.node, endOffset: b.offset });
    }
    function customHighlight() {
        return window.CSS && CSS.highlights && typeof window.Highlight === 'function';
    }
    function contentBounds() {
        var margins = window.InkReadFormatting && InkReadFormatting.effectiveMargins ? InkReadFormatting.effectiveMargins() : {left:0,right:0,top:0,bottom:0};
        return { left: Math.max(0, margins.left), right: Math.min(window.innerWidth, window.innerWidth - margins.right),
            top: Math.max(0, margins.top), bottom: Math.min(window.innerHeight, window.innerHeight - margins.bottom) };
    }
    function rangeContainsPoint(range, x, y) {
        var bounds = contentBounds(), rects = range.getClientRects();
        for (var i = 0; i < rects.length; i++) {
            var rect = rects[i], left = Math.max(bounds.left, rect.left), right = Math.min(bounds.right, rect.right);
            var top = Math.max(bounds.top, rect.top), bottom = Math.min(bounds.bottom, rect.bottom);
            if (x >= left && x < right && y >= top && y < bottom) return true;
        }
        return false;
    }
    function hasSelection() {
        var selection = window.getSelection();
        return selection && selection.rangeCount > 0 && !selection.isCollapsed;
    }
    function selectionContainsPoint(x, y, expectedGeneration) {
        if (!live(expectedGeneration) || typeof x !== 'number' || !isFinite(x) || typeof y !== 'number' || !isFinite(y) || !hasSelection()) return false;
        var selection = window.getSelection();
        for (var i = 0; i < selection.rangeCount; i++) if (rangeContainsPoint(selection.getRangeAt(i), x, y)) return true;
        return false;
    }
    function interactiveElement(element) {
        for (var node = element; node; node = node.parentElement) {
            if (/^(A|BUTTON|INPUT|SELECT|TEXTAREA|LABEL|OPTION|SUMMARY|AUDIO|VIDEO)$/.test(node.tagName) || node.isContentEditable ||
                (node.hasAttribute && node.hasAttribute('contenteditable'))) return true;
            var role = node.getAttribute && node.getAttribute('role');
            if (role === 'button' || role === 'link') return true;
        }
        return false;
    }
    function tapTarget(x, y, expectedGeneration) {
        if (!live(expectedGeneration) || typeof x !== 'number' || !isFinite(x) || typeof y !== 'number' || !isFinite(y) || hasSelection()) return '{"kind":"blocked"}';
        // Native links/buttons win over annotations, including an inline child
        // inside the link. Selection wins over both, even with capture disabled.
        var element = document.elementFromPoint(x, y);
        if (!element || interactiveElement(element)) return '{"kind":"blocked"}';
        var hits = attached.filter(function (entry) { return rangeContainsPoint(entry.range, x, y); });
        hits.sort(function (a, b) { return (a.end - a.start) - (b.end - b.start) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0); });
        return hits.length ? JSON.stringify({kind:'annotation', id:hits[0].id}) : '{"kind":"page"}';
    }
    function draw() {
        redrawFrame = 0;
        if (!live(generation) || !frame) return;
        if (customHighlight()) {
            try { var highlight = new Highlight(); ranges.forEach(function (r) { highlight.add(r); }); CSS.highlights.set('inkread-annotation', highlight); return; } catch (ignored) {}
        }
        if (!overlay) {
            overlay = document.createElement('div'); overlay.id = 'inkread-annotation-overlay';
            overlay.setAttribute('aria-hidden', 'true'); overlay.setAttribute('inert', '');
            overlay.style.cssText = 'position:fixed;inset:0;overflow:hidden;pointer-events:none;z-index:1;contain:strict;';
            document.body.appendChild(overlay);
        }
        while (overlay.firstChild) overlay.removeChild(overlay.firstChild);
        var margins = window.InkReadFormatting && InkReadFormatting.effectiveMargins ? InkReadFormatting.effectiveMargins() : {left:0,right:0,top:0,bottom:0};
        ranges.forEach(function (range) { Array.prototype.forEach.call(range.getClientRects(), function (rect) {
            var left = Math.max(margins.left, rect.left), right = Math.min(window.innerWidth - margins.right, rect.right);
            var top = Math.max(margins.top, rect.top), bottom = Math.min(window.innerHeight - margins.bottom, rect.bottom);
            if (right <= left || bottom <= top) return;
            var mark = document.createElement('div');
            // Monochrome underline is legible without covering the original text.
            mark.style.cssText = 'position:absolute;border-bottom:2px solid #555;background:rgba(0,0,0,.10);pointer-events:none;' +
                'left:' + left + 'px;top:' + top + 'px;width:' + (right-left) + 'px;height:' + (bottom-top) + 'px;';
            overlay.appendChild(mark);
        }); });
    }
    function clearMarks() {
        if (redrawFrame) { cancelAnimationFrame(redrawFrame); redrawFrame = 0; }
        if (customHighlight()) CSS.highlights.delete('inkread-annotation');
        if (overlay) { overlay.remove(); overlay = null; }
    }
    function redraw() {
        if (!destroyed && ranges.length && !redrawFrame) redrawFrame = requestAnimationFrame(draw);
    }
    function setAnnotations(value, expectedGeneration) {
        if (!live(expectedGeneration) || !Array.isArray(value)) return false;
        if (value.length && !ensureMap()) return false;
        annotations = value; locations = []; ranges = []; attached = [];
        annotations.forEach(function (annotation) {
            var resolved = locate(annotation.anchor), range = resolved && makeRange(resolved.start, resolved.end);
            locations.push({ id: annotation.id, status: range ? 'attached' : 'unmatched' });
            if (range) { ranges.push(range); attached.push({id:annotation.id, start:resolved.start, end:resolved.end, range:range}); }
        });
        notify('onAnnotationLocations', JSON.stringify(locations));
        if (ranges.length) redraw(); else clearMarks();
        return true;
    }
    function pageForAnnotation(id, expectedGeneration) {
        if (!live(expectedGeneration) || !map) return null;
        var entry = annotations.filter(function (a) { return a.id === id; })[0];
        var location = entry && locate(entry.anchor), range = location && makeRange(location.start, location.end);
        if (!range || typeof window._pageStride !== 'number' || window._pageStride <= 0) return null;
        var rects = range.getClientRects(); if (!rects.length) return null;
        var margins = window.InkReadFormatting.effectiveMargins();
        return Math.max(0, Math.floor((rects[0].left + window._currentPage * window._pageStride - margins.left + .5) / window._pageStride));
    }
    function clearSelection() { if (!live(generation)) return; cancelPendingAuto(); var s = window.getSelection(); if (s) s.removeAllRanges(); redraw(); }
    function setSelectionMode(value, expectedGeneration, clearRange) {
        if (!live(expectedGeneration)) return false;
        var wasEnabled = enabled;
        enabled = !!value;
        if (!enabled) cancelPendingAuto();
        // Native Copy owns its own asynchronous range cleanup. State-only sync
        // must not remove its range before Chromium has copied it.
        if (!enabled && clearRange !== false) clearSelection();
        if (enabled && !wasEnabled && autoGesture && autoGesture.completed) scheduleAutoCapture(autoGesture);
        return true;
    }
    function validToken(token) { return typeof token === 'string' && token.length > 0; }
    function beginResize(id, requestToken, expectedGeneration) {
        if (!live(expectedGeneration) || !validToken(requestToken) || typeof id !== 'string' || !ensureMap()) return false;
        var entries = annotations.filter(function (entry) { return entry.id === id; });
        if (entries.length !== 1) return false;
        var location = locate(entries[0].anchor), range = location && makeRange(location.start, location.end), selection = window.getSelection();
        if (!range || !selection) return false;
        cancelPendingAuto();
        // The token/ID/generation identify one immutable native edit request.
        // Moving handles only changes the browser range, never the saved anchor.
        resizeSession = {id:id, token:requestToken, generation:expectedGeneration};
        enabled = true; selection.removeAllRanges(); selection.addRange(range); redraw();
        return true;
    }
    function activeResize(id, token, expectedGeneration) {
        return live(expectedGeneration) && resizeSession && resizeSession.id === id && resizeSession.token === token && resizeSession.generation === expectedGeneration;
    }
    function captureResize(id, requestToken, expectedGeneration) {
        return activeResize(id, requestToken, expectedGeneration) ? captureSelection(expectedGeneration) : null;
    }
    function cancelResize(id, requestToken, expectedGeneration, clearRange) {
        if (!activeResize(id, requestToken, expectedGeneration)) return false;
        resizeSession = null; cancelPendingAuto();
        // Copy's asynchronous native serialization still owns the live range.
        // Revoke this edit request without clearing it on that completion path.
        if (clearRange !== false) clearSelection();
        return true;
    }
    function cancelPendingAuto() {
        if (autoFrame) { cancelAnimationFrame(autoFrame); autoFrame = 0; }
        autoGesture = null;
    }
    function cancelAutoCapture(expectedGeneration) {
        if (!live(expectedGeneration)) return false;
        cancelPendingAuto(); return true;
    }
    function endSelectionSession(expectedGeneration) {
        if (!live(expectedGeneration)) return false;
        cancelPendingAuto(); autoConsumed = false;
        // A bridge emission is not a successful host save. Only attached saved
        // annotations can deduplicate a future session; rejection, failure,
        // deletion and Undo must leave that future selection retryable.
        sessionEmittedRanges = []; lastAutoEmittedKey = null; return true;
    }
    function forgetLastAutoCapture(expectedGeneration) {
        if (!live(expectedGeneration) || lastAutoEmittedKey === null) return false;
        var index = sessionEmittedRanges.indexOf(lastAutoEmittedKey);
        if (index >= 0) sessionEmittedRanges.splice(index, 1);
        // Undo only makes the last emitted range eligible for a future native
        // session. It must not rearm handles still visible in this session.
        lastAutoEmittedKey = null; return true;
    }
    function setAutoHighlightEnabled(value, expectedGeneration) {
        if (!live(expectedGeneration)) return false;
        autoEnabled = !!value;
        if (!autoEnabled) cancelPendingAuto();
        return true;
    }
    function beginSelectionGesture(token, expectedGeneration) {
        if (!live(expectedGeneration) || !validToken(token)) return false;
        cancelPendingAuto();
        if (!autoEnabled || autoConsumed || resizeSession) return false;
        // Only native can arm this, from the original single-pointer DOWN.
        // DOM touch events cannot represent Chromium's selection-handle popup.
        autoGesture = {token:token, completed:false}; return true;
    }
    function eligibleAuto(gesture) {
        return live(generation) && autoEnabled && enabled && !resizeSession && !autoConsumed && autoGesture === gesture && gesture.completed;
    }
    function autoCaptureValue() {
        var json = captureSelection(generation);
        if (!json) return null;
        var value = JSON.parse(json);
        return value.error ? null : {json:json, anchor:value};
    }
    function scheduleAutoCapture(gesture) {
        if (autoFrame) { cancelAnimationFrame(autoFrame); autoFrame = 0; }
        if (!eligibleAuto(gesture)) return;
        var candidate = autoCaptureValue(), revision = selectionRevision;
        if (!candidate) return; // A later selectionchange can supply the range.
        var stableFrames = 0;
        function check() {
            autoFrame = 0;
            if (!eligibleAuto(gesture)) return;
            var next = autoCaptureValue();
            if (!next) return;
            if (revision !== selectionRevision || next.json !== candidate.json) {
                candidate = next; revision = selectionRevision; stableFrames = 0;
            } else stableFrames++;
            if (stableFrames < 2) { autoFrame = requestAnimationFrame(check); return; }
            var anchor = candidate.anchor;
            var key = anchor.documentFingerprint + ':' + anchor.start + ':' + anchor.end;
            var duplicate = sessionEmittedRanges.indexOf(key) >= 0 || attached.some(function (entry) { return entry.start === anchor.start && entry.end === anchor.end; });
            // Consume even a duplicate, so adjusting an existing selection does
            // not turn it into another automatic write within this same session.
            autoConsumed = true; autoGesture = null;
            if (duplicate) return;
            try {
                if (window.Android && typeof Android.onAutoAnnotation === 'function') {
                    sessionEmittedRanges.push(key);
                    lastAutoEmittedKey = key;
                    Android.onAutoAnnotation(generation, gesture.token, candidate.json);
                }
            } catch (ignored) {}
        }
        autoFrame = requestAnimationFrame(check);
    }
    function completeSelectionGesture(token, expectedGeneration) {
        if (!live(expectedGeneration) || !autoGesture || autoGesture.token !== token || autoGesture.completed || !autoEnabled || resizeSession || autoConsumed) return false;
        autoGesture.completed = true; scheduleAutoCapture(autoGesture); return true;
    }
    function selectionChanged() {
        selectionRevision++; redraw();
        if (!live(generation)) { cancelPendingAuto(); return; }
        if (autoGesture && autoGesture.completed) scheduleAutoCapture(autoGesture);
    }
    function preventBlankMarginSelection(event) {
        if (destroyed || enabled || !event.cancelable || !event.touches || event.touches.length !== 1) return;
        var selection = window.getSelection();
        if (selection && !selection.isCollapsed) return;
        // Article text, links and media are descendants of #eink-content. Only
        // the empty BODY/HTML outside it is ours; leave every descendant native.
        if (event.target !== document.body && event.target !== document.documentElement) return;
        // Cancel at touch-start acknowledgement, before Chromium can turn a
        // margin hold into cached selection handles without creating ActionMode.
        // Kotlin still receives the real stream and owns page taps/swipes.
        event.preventDefault();
    }
    function initialize() {
        frame = document.getElementById('eink-content'); if (!frame) return;
        var style = document.createElement('style');
        style.textContent = '::highlight(inkread-annotation){background-color:#ddd;color:#000;text-decoration:underline;}' +
            '#eink-content .eink-metadata{-webkit-user-select:none;user-select:none;}';
        document.head.appendChild(style);
        frame.addEventListener('load', redraw, true); frame.addEventListener('error', redraw, true);
        // Existing pagination changes the frame transform/style. Observe it rather
        // than wrapping its API or touching its command IDs or resume indices.
        if (window.MutationObserver) { observer = new MutationObserver(redraw); observer.observe(frame, {attributes:true, attributeFilter:['style']}); }
        if (document.fonts) document.fonts.addEventListener('loadingdone', redraw);
        document.addEventListener('selectionchange', selectionChanged);
        document.addEventListener('touchstart', preventBlankMarginSelection, {passive:false});
        window.addEventListener('resize', redraw);
        window.addEventListener('scroll', redraw, true);
    }
    window.InkReadAnnotations = {
        documentId: generation, pageForAnnotation: pageForAnnotation, captureSelection: captureSelection, setAnnotations: setAnnotations,
        setSelectionMode: setSelectionMode, clearSelection: clearSelection, redraw: redraw,
        tapTarget: tapTarget, selectionContainsPoint: selectionContainsPoint,
        beginResize: beginResize, captureResize: captureResize, cancelResize: cancelResize,
        setAutoHighlightEnabled: setAutoHighlightEnabled, beginSelectionGesture: beginSelectionGesture,
        completeSelectionGesture: completeSelectionGesture, cancelAutoCapture: cancelAutoCapture, endSelectionSession: endSelectionSession,
        forgetLastAutoCapture: forgetLastAutoCapture,
        // Read-only diagnostics useful to native regression tests.
        textMapVersion: VERSION,
        locationStatus: function () { return locations.slice(); }
    };
    window.addEventListener('pagehide', function () {
        destroyed = true; cancelPendingAuto(); resizeSession = null; if (redrawFrame) cancelAnimationFrame(redrawFrame);
        if (observer) observer.disconnect(); if (overlay) overlay.remove();
        if (customHighlight()) CSS.highlights.delete('inkread-annotation');
        if (frame) { frame.removeEventListener('load', redraw, true); frame.removeEventListener('error', redraw, true); }
        if (document.fonts) document.fonts.removeEventListener('loadingdone', redraw);
        document.removeEventListener('selectionchange', selectionChanged);
        document.removeEventListener('touchstart', preventBlankMarginSelection);
        window.removeEventListener('resize', redraw); window.removeEventListener('scroll', redraw, true);
    }, {once:true});
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, {once:true}); else initialize();
})();
