/* Body-only annotation anchors. Never wraps, splits, or edits article text nodes. */
(function () {
    'use strict';
    var VERSION = 1, CONTEXT = 32, CAPTURE_BYTE_LIMIT = 128 * 1024, frame, map, enabled = false, destroyed = false;
    var annotations = [], locations = [], ranges = [], redrawFrame = 0, overlay, observer;
    var generation = window._inkreadGeneration;
    var OMIT = /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE)$/;
    function notify(name, value) {
        try { if (window.Android && typeof Android[name] === 'function') Android[name](generation, value); } catch (ignored) {}
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
        if (destroyed || !enabled || generation !== expectedGeneration) return null;
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
    function draw() {
        redrawFrame = 0;
        if (destroyed || !frame) return;
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
        if (destroyed || generation !== expectedGeneration || !Array.isArray(value)) return false;
        if (value.length && !ensureMap()) return false;
        annotations = value; locations = []; ranges = [];
        annotations.forEach(function (annotation) {
            var resolved = locate(annotation.anchor), range = resolved && makeRange(resolved.start, resolved.end);
            locations.push({ id: annotation.id, status: range ? 'attached' : 'unmatched' });
            if (range) ranges.push(range);
        });
        notify('onAnnotationLocations', JSON.stringify(locations));
        if (ranges.length) redraw(); else clearMarks();
        return true;
    }
    function pageForAnnotation(id, expectedGeneration) {
        if (destroyed || generation !== expectedGeneration || !map) return null;
        var entry = annotations.filter(function (a) { return a.id === id; })[0];
        var location = entry && locate(entry.anchor), range = location && makeRange(location.start, location.end);
        if (!range || typeof window._pageStride !== 'number' || window._pageStride <= 0) return null;
        var rects = range.getClientRects(); if (!rects.length) return null;
        var margins = window.InkReadFormatting.effectiveMargins();
        return Math.max(0, Math.floor((rects[0].left + window._currentPage * window._pageStride - margins.left + .5) / window._pageStride));
    }
    function clearSelection() { var s = window.getSelection(); if (s) s.removeAllRanges(); redraw(); }
    function setSelectionMode(value, expectedGeneration, clearRange) {
        if (destroyed || generation !== expectedGeneration) return false;
        enabled = !!value;
        // Native Copy owns its own asynchronous range cleanup. State-only sync
        // must not remove its range before Chromium has copied it.
        if (!enabled && clearRange !== false) clearSelection();
        return true;
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
        document.addEventListener('selectionchange', redraw);
        window.addEventListener('resize', redraw);
        window.addEventListener('scroll', redraw, true);
    }
    window.InkReadAnnotations = {
        documentId: generation, pageForAnnotation: pageForAnnotation, captureSelection: captureSelection, setAnnotations: setAnnotations,
        setSelectionMode: setSelectionMode, clearSelection: clearSelection, redraw: redraw,
        // Read-only diagnostics useful to native regression tests.
        textMapVersion: VERSION,
        locationStatus: function () { return locations.slice(); }
    };
    window.addEventListener('pagehide', function () {
        destroyed = true; if (redrawFrame) cancelAnimationFrame(redrawFrame);
        if (observer) observer.disconnect(); if (overlay) overlay.remove();
        if (customHighlight()) CSS.highlights.delete('inkread-annotation');
        if (frame) { frame.removeEventListener('load', redraw, true); frame.removeEventListener('error', redraw, true); }
        if (document.fonts) document.fonts.removeEventListener('loadingdone', redraw);
        document.removeEventListener('selectionchange', redraw); window.removeEventListener('resize', redraw); window.removeEventListener('scroll', redraw, true);
    }, {once:true});
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, {once:true}); else initialize();
})();
