/* One-time presentation wrappers with stable, original text-node coordinates.
 * Formatting updates change CSS only. The source text and its logical node order
 * remain identical, including whitespace, for old reading/annotation anchors.
 */
(function () {
    'use strict';
    var installed = new WeakMap();
    var OMIT = /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE)$/;
    var READING_OMIT = /^(SCRIPT|STYLE|NOSCRIPT)$/;
    // Preserve code/math/SVG and raw-text/form semantics. Their existing font
    // family and English/base size remain authoritative, as before.
    var NO_WRAP = /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE|PRE|CODE|MATH|SVG|TEXTAREA|SELECT|OPTION|INPUT)$/;

    function integer(value) {
        return typeof value === 'number' && isFinite(value) && Math.floor(value) === value;
    }
    function inRange(value, first, last) { return value >= first && value <= last; }
    function cjk(code) {
        // Match EInkReaderFormatting.CJK_RANGE, including full-width punctuation,
        // kana, bopomofo, compatibility ideographs and supplementary Han.
        return inRange(code, 0x2e80, 0x2fff) || inRange(code, 0x3000, 0x303f) ||
            inRange(code, 0x3040, 0x30ff) || inRange(code, 0x3100, 0x312f) ||
            inRange(code, 0x31a0, 0x31bf) || inRange(code, 0x31f0, 0x31ff) ||
            inRange(code, 0x3400, 0x4dbf) || inRange(code, 0x4e00, 0x9fff) ||
            inRange(code, 0xf900, 0xfaff) || inRange(code, 0xfe10, 0xfe1f) ||
            inRange(code, 0xfe30, 0xfe4f) || inRange(code, 0xff00, 0xffef) ||
            inRange(code, 0x20000, 0x2ffff) || inRange(code, 0x30000, 0x323af);
    }
    function continuation(code) {
        // Keep combining/variation characters with their preceding base. These
        // explicit ranges work on the older WebViews supported by the app too.
        return inRange(code, 0x0300, 0x036f) || inRange(code, 0x1ab0, 0x1aff) ||
            inRange(code, 0x1dc0, 0x1dff) || inRange(code, 0x20d0, 0x20ff) ||
            inRange(code, 0xfe00, 0xfe0f) || inRange(code, 0xfe20, 0xfe2f) ||
            inRange(code, 0xe0100, 0xe01ef) || inRange(code, 0x1f3fb, 0x1f3ff) ||
            code === 0x200c || code === 0x200d;
    }
    function runs(text) {
        var result = [], start = 0, previous = false, joined = false;
        for (var offset = 0; offset < text.length;) {
            var first = text.charCodeAt(offset), code = first, width = 1;
            if (inRange(first, 0xd800, 0xdbff) && offset + 1 < text.length) {
                var second = text.charCodeAt(offset + 1);
                if (inRange(second, 0xdc00, 0xdfff)) {
                    code = 0x10000 + ((first - 0xd800) << 10) + second - 0xdc00;
                    width = 2;
                }
            }
            var chinese = offset > 0 && (continuation(code) || joined) ? previous : cjk(code);
            if (offset > 0 && chinese !== previous) {
                result.push({ start: start, end: offset, chinese: previous });
                start = offset;
            }
            previous = chinese;
            joined = code === 0x200d;
            offset += width;
        }
        if (text.length) result.push({ start: start, end: text.length, chinese: previous });
        return result;
    }
    function install(root) {
        if (!root) return null;
        if (installed.has(root)) return installed.get(root);
        var document = root.ownerDocument || window.document;
        var nodes = [], readingNodes = [], annotationNodes = [], bindings = new WeakMap();

        function visit(node, annotationAllowed, wrappingAllowed) {
            if (node.nodeType !== 3) {
                var tag = String(node.tagName || '').toUpperCase();
                if (node !== root && (OMIT.test(tag) ||
                    (node.classList && node.classList.contains('eink-metadata')))) annotationAllowed = false;
                if (NO_WRAP.test(tag)) wrappingAllowed = false;
                for (var child = node.firstChild; child; child = child.nextSibling) {
                    visit(child, annotationAllowed, wrappingAllowed);
                }
                return;
            }
            var record = { nodeType: 3, data: node.data, length: node.data.length,
                parentElement: node.parentElement, fragments: [] };
            nodes.push(record);
            var parent = record.parentElement;
            if (parent && !READING_OMIT.test(String(parent.tagName || '').toUpperCase()) && /\S/.test(record.data)) {
                readingNodes.push(record);
            }
            if (annotationAllowed) annotationNodes.push(record);
            bindings.set(node, { node: record, start: 0 });
            record.fragments.push({ node: node, start: 0, end: record.length });
            // Save eligibility before inserting anything; traversal never sees
            // newly created wrappers or fragments.
            record._wrap = wrappingAllowed;
        }
        visit(root, true, true);

        nodes.forEach(function (record) {
            var original = record.fragments[0].node, pieces = record._wrap ? runs(record.data) : [];
            delete record._wrap;
            if (!pieces.some(function (piece) { return piece.chinese; })) return;
            var parent = original.parentNode;
            if (!parent) return;
            var fragment = document.createDocumentFragment();
            record.fragments = [];
            pieces.forEach(function (piece) {
                var text = document.createTextNode(record.data.slice(piece.start, piece.end));
                var entry = { node: text, start: piece.start, end: piece.end };
                bindings.set(text, { node: record, start: piece.start });
                record.fragments.push(entry);
                if (piece.chinese) {
                    var span = document.createElement('span');
                    span.className = 'eink-script-cjk';
                    span.appendChild(text);
                    fragment.appendChild(span);
                } else fragment.appendChild(text);
            });
            // Never splitText(), normalize(), trim(), or rewrite source data.
            // The disconnected original retains its exact text for diagnostics.
            parent.replaceChild(fragment, original);
        });

        function point(record, offset, end) {
            if (!record || !record.fragments || !integer(offset) || offset < 0 || offset > record.length) return null;
            var fragments = record.fragments, low = 0, high = fragments.length - 1;
            // Native Range offsets are UTF-16. Geometry probes may intentionally
            // sample a surrogate midpoint; only wrapper boundaries are scalar.
            // At joins choose next for starts, previous for ends.
            while (low < high) {
                var middle = (low + high) >> 1;
                if (fragments[middle].end < offset || (!end && fragments[middle].end === offset)) low = middle + 1;
                else high = middle;
            }
            var piece = fragments[low];
            return piece ? { node: piece.node, offset: offset - piece.start } : null;
        }
        function descendantPoint(node, forward) {
            if (node.nodeType === 3) {
                var binding = bindings.get(node);
                return binding ? { node: binding.node, offset: binding.start + (forward ? 0 : node.data.length) } : null;
            }
            var children = node.childNodes || [];
            for (var i = forward ? 0 : children.length - 1; forward ? i < children.length : i >= 0; i += forward ? 1 : -1) {
                var result = descendantPoint(children[i], forward);
                if (result) return result;
            }
            return null;
        }
        function adjacentPoint(node, offset, forward) {
            var current = node, boundary = offset;
            while (current) {
                var children = current.childNodes || [];
                for (var i = forward ? boundary : boundary - 1; forward ? i < children.length : i >= 0; i += forward ? 1 : -1) {
                    var result = descendantPoint(children[i], forward);
                    if (result) return result;
                }
                if (current === root) return null;
                var parent = current.parentNode;
                if (!parent) return null;
                var index = Array.prototype.indexOf.call(parent.childNodes, current);
                boundary = index + (forward ? 1 : 0);
                current = parent;
            }
            return null;
        }
        function logicalPoint(node, offset, end) {
            if (!node || !integer(offset) || offset < 0) return null;
            var entry = bindings.get(node);
            if (entry) return offset <= node.data.length ? { node: entry.node, offset: entry.start + offset } : null;
            if (node.nodeType === 3 || !node.childNodes || offset > node.childNodes.length) return null;
            var ancestor = node;
            while (ancestor && ancestor !== root) ancestor = ancestor.parentNode;
            if (ancestor !== root) return null;
            // Browser selections may use a generated span or link's child
            // boundary instead of a text endpoint. Do not widen such a boundary
            // to its whole original source node. Empty element boundaries can
            // resolve through adjacent siblings, but never outside this root.
            return adjacentPoint(node, offset, !end) || adjacentPoint(node, offset, !!end);
        }
        function range(record, start, end) {
            var a = point(record, start, false), b = point(record, end, true);
            if (!a || !b || end < start) return null;
            var result = document.createRange();
            result.setStart(a.node, a.offset); result.setEnd(b.node, b.offset);
            return result;
        }
        function intersects(range, record) {
            return !!record && record.fragments.some(function (piece) { return range.intersectsNode(piece.node); });
        }
        var model = { nodes: nodes, readingNodes: readingNodes, annotationNodes: annotationNodes,
            point: point, endpoint: point, logicalPoint: logicalPoint, logicalEndpoint: logicalPoint,
            range: range, intersects: intersects,
            original: function (node) { var entry = bindings.get(node); return entry ? entry.node : null; } };
        installed.set(root, model);
        return model;
    }
    window.InkReadScriptFonts = { install: install };
})();
