const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/assets/eink-script-fonts.js'), 'utf8');
const annotationsSource = fs.readFileSync(path.join(__dirname, '../../main/assets/eink-annotations.js'), 'utf8');

function fixture(build, {install = true} = {}) {
    const document = {};
    class Node {
        constructor(type, tag, data) {
            this.nodeType = type; this.tagName = tag; this.data = data; this.childNodes = [];
            this.parentNode = null; this.className = ''; this.ownerDocument = document;
            this.style = {}; this.attributes = {};
            this.classList = {contains: name => this.className.split(/\s+/).includes(name)};
        }
        get length() { return this.data.length; }
        get parentElement() { return this.parentNode?.nodeType === 1 ? this.parentNode : null; }
        get firstChild() { return this.childNodes[0] || null; }
        get nextSibling() {
            if (!this.parentNode) return null;
            const siblings = this.parentNode.childNodes;
            return siblings[siblings.indexOf(this) + 1] || null;
        }
        get textContent() { return this.nodeType === 3 ? this.data : this.childNodes.map(n => n.textContent).join(''); }
        set textContent(value) {
            if (this.nodeType === 3) this.data = value;
            else { this.childNodes.slice().forEach(child => this.removeChild(child)); if (value) this.appendChild(new Node(3, undefined, value)); }
        }
        setAttribute(name, value) { this.attributes[name] = value; }
        addEventListener() {}
        removeEventListener() {}
        getBoundingClientRect() { return {left:0, top:0, right:400, bottom:600, width:400, height:600}; }
        appendChild(node) {
            if (node.parentNode) node.parentNode.removeChild(node);
            this.childNodes.push(node); node.parentNode = this; return node;
        }
        removeChild(node) { this.childNodes.splice(this.childNodes.indexOf(node), 1); node.parentNode = null; return node; }
        replaceChild(next, previous) {
            const index = this.childNodes.indexOf(previous);
            assert.notEqual(index, -1);
            const replacement = next.nodeType === 11 ? [...next.childNodes] : [next];
            replacement.forEach(node => { if (node.parentNode) node.parentNode.removeChild(node); node.parentNode = this; });
            this.childNodes.splice(index, 1, ...replacement); previous.parentNode = null;
            return previous;
        }
    }
    const text = data => new Node(3, undefined, data);
    const element = (tag, ...children) => {
        const result = new Node(1, tag.toUpperCase());
        children.forEach(child => result.appendChild(typeof child === 'string' ? text(child) : child));
        return result;
    };
    Object.assign(document, {
        createTextNode: text, createElement: tag => element(tag), createDocumentFragment: () => new Node(11)
    });
    const root = element('MAIN', ...build({text, element}));
    function allText(node = root) { return node.nodeType === 3 ? [node] : node.childNodes.flatMap(allText); }
    function wrappers(node = root) {
        return [...(node.className === 'eink-script-cjk' ? [node] : []), ...node.childNodes.flatMap(wrappers)];
    }
    const originals = allText().map(node => ({node, data: node.data, parentElement: node.parentElement}));
    function absolute(node, offset) {
        let result = 0, found = null;
        function visit(current) {
            if (found !== null) return;
            if (current === node) {
                found = result + (current.nodeType === 3 ? offset : current.childNodes.slice(0, offset).reduce((sum, child) => sum + child.textContent.length, 0));
            } else if (current.nodeType === 3) result += current.length;
            else current.childNodes.forEach(visit);
        }
        visit(root);
        if (found === null) throw Error('Range endpoint is not in document');
        return found;
    }
    document.createRange = () => ({
        startContainer: null, endContainer: null, startOffset: 0, endOffset: 0,
        setStart(node, offset) { this.startContainer = node; this.startOffset = offset; },
        setEnd(node, offset) { this.endContainer = node; this.endOffset = offset; },
        selectNodeContents(node) { this.setStart(node, 0); this.setEnd(node, node.nodeType === 3 ? node.length : node.childNodes.length); },
        toString() { return root.textContent.slice(absolute(this.startContainer, this.startOffset), absolute(this.endContainer, this.endOffset)); },
        getClientRects() {
            const start = absolute(this.startContainer, this.startOffset), left = 10 + start % 30 * 8;
            return [{left, right:left+8, top:20, bottom:40, width:8, height:20}];
        },
        intersectsNode(node) {
            const start = absolute(node, 0), end = start + node.length;
            return end > absolute(this.startContainer, this.startOffset) && start < absolute(this.endContainer, this.endOffset);
        }
    });
    const context = {document, WeakMap, Math}; context.window = context;
    vm.createContext(context);
    if (install) vm.runInContext(source, context);
    const model = install ? context.InkReadScriptFonts.install(root) : null;
    return {root, model, originals, allText, wrappers, context, text, element, document};
}
const data = records => Array.from(records, node => node.data);

function withAnnotations(f) {
    let selected = null, sequence = 0;
    const queue = new Map(), selection = {
        get rangeCount() { return selected ? 1 : 0; },
        get isCollapsed() { return !selected || selected.toString() === ''; },
        getRangeAt: () => selected, toString: () => selected ? selected.toString() : '',
        removeAllRanges() { selected = null; }, addRange(range) { selected = range; }
    };
    Object.assign(f.document, {head:f.element('HEAD'), body:f.element('BODY', f.root), readyState:'complete',
        getElementById:id=>id==='eink-content'?f.root:null, addEventListener() {}, removeEventListener() {}});
    Object.assign(f.context, {_inkreadGeneration:'test', _pageStride:400, _currentPage:0,
        innerWidth:400, innerHeight:600, getSelection:()=>selection,
        addEventListener() {}, removeEventListener() {},
        requestAnimationFrame(fn) { queue.set(++sequence, fn); return sequence; }, cancelAnimationFrame:id=>queue.delete(id),
        CSS:{highlights:new Map()}, Highlight:class extends Set {},
        InkReadFormatting:{effectiveMargins:()=>({left:0, right:0, top:0, bottom:0})}});
    vm.runInContext(annotationsSource, f.context);
    const api = f.context.InkReadAnnotations;
    api.setSelectionMode(true, 'test');
    function selectOriginal(a, start, b, end) {
        const range = f.document.createRange();
        const first = f.model ? f.model.point(f.model.nodes[a], start, false) : {node:f.originals[a].node, offset:start};
        const last = f.model ? f.model.point(f.model.nodes[b], end, true) : {node:f.originals[b].node, offset:end};
        range.setStart(first.node, first.offset); range.setEnd(last.node, last.offset);
        selected = range;
    }
    function flush() { while (queue.size) { const jobs = [...queue.values()]; queue.clear(); jobs.forEach(fn=>fn()); } }
    return {...f, api, selection, selectOriginal, flush,
        selectRange:range=>{ selected=range; }, capture:()=>JSON.parse(api.captureSelection('test')),
        statuses:()=>JSON.parse(JSON.stringify(api.locationStatus()))};
}

test('one-time CJK runs preserve exact text and original logical records', () => {
    const value = '  English中文，English\n漢字。\t';
    const f = fixture(({element}) => [element('P', value)]);
    assert.equal(f.root.textContent, value);
    assert.deepEqual(data(f.model.nodes), [value]);
    assert.deepEqual(f.wrappers().map(n => n.textContent), ['中文，', '漢字。']);
    assert.deepEqual(f.allText().map(n => n.data), ['  English', '中文，', 'English\n', '漢字。', '\t']);
    assert.equal(f.model.nodes[0].parentElement, f.originals[0].parentElement);
    assert.equal(f.originals[0].node.data, value);
    const physical = f.allText(), wrappers = f.wrappers();
    for (let i = 0; i < 20; i++) assert.equal(f.context.InkReadScriptFonts.install(f.root), f.model);
    assert.deepEqual(f.allText(), physical); assert.deepEqual(f.wrappers(), wrappers);
    assert.equal(f.wrappers().some(n => n.parentElement.className === 'eink-script-cjk'), false);
});

test('reading records exactly preserve old direct-parent and whitespace filtering', () => {
    const f = fixture(({element}) => [element('H1', '标题'), '\n ', element('P', 'Body正文'),
        element('SCRIPT', 'script'), element('STYLE', 'style'), element('NOSCRIPT', 'hidden'),
        element('NOSCRIPT', element('SPAN', 'nested old reading record')), element('TEMPLATE', 'template old reading record')]);
    assert.deepEqual(data(f.model.readingNodes), ['标题', 'Body正文', 'nested old reading record', 'template old reading record']);
    assert.equal(f.model.nodes.length, 8);
});

test('annotation records retain whitespace but omit metadata and all OMIT descendants', () => {
    const f = fixture(({element}) => {
        const metadata = element('DIV', element('H1', '标题'), element('P', 'Byline'));
        metadata.className = 'eink-metadata';
        return [metadata, '\n ', element('P', 'Body正文'), element('SCRIPT', 'script'),
            element('STYLE', element('SPAN', 'nested style')), element('NOSCRIPT', element('B', 'hidden')),
            element('TEMPLATE', element('B', 'template')), element('CODE', 'code注释')];
    });
    assert.deepEqual(data(f.model.annotationNodes), ['\n ', 'Body正文', 'code注释']);
    assert.deepEqual(f.wrappers().map(n => n.textContent), ['标题', '正文']);
});

test('code, pre, math, SVG and form/raw text stay completely unwrapped', () => {
    const f = fixture(({element}) => ['plain中文', ...['CODE','PRE','MATH','SVG','TEXTAREA','SELECT','OPTION','INPUT']
        .map(tag => element(tag, element('SPAN', tag + '中文')))]);
    assert.deepEqual(f.wrappers().map(n => n.textContent), ['中文']);
    f.model.nodes.slice(1).forEach((record, index) => {
        assert.equal(record.fragments.length, 1);
        assert.equal(record.fragments[0].node, f.originals[index + 1].node);
    });
});

test('supplementary Han, variation selectors and combining marks never split their base', () => {
    const han = '\u{20000}\u{E0100}', value = 'A\u0301' + han + '漢\uFE00\u0301' + '😀\uFE0F' + '文';
    const f = fixture(({element}) => [element('P', value)]);
    assert.equal(f.root.textContent, value);
    assert.deepEqual(f.allText().map(n => n.data), ['A\u0301', han + '漢\uFE00\u0301', '😀\uFE0F', '文']);
    assert.deepEqual(f.wrappers().map(n => n.textContent), [han + '漢\uFE00\u0301', '文']);
    f.model.nodes[0].fragments.forEach(piece => {
        assert.equal(/[\uDC00-\uDFFF]/.test(piece.node.data.charAt(0)), false);
        assert.equal(/[\uD800-\uDBFF]/.test(piece.node.data.charAt(piece.node.data.length - 1)), false);
    });
});

test('CJK punctuation matches existing CJK face ranges; ASCII punctuation remains base-sized', () => {
    const f = fixture(() => ['“A” (漢)，。！？　カナㄅ\u{30000}Z']);
    assert.deepEqual(f.wrappers().map(n => n.textContent), ['漢', '，。！？　カナㄅ\u{30000}']);
    assert.equal(f.root.textContent, '“A” (漢)，。！？　カナㄅ\u{30000}Z');
});

test('logical/physical offsets round-trip in UTF-16 including surrogate geometry probes', () => {
    const f = fixture(() => ['A漢\u{20000}\u{E0100}B文C']);
    const record = f.model.nodes[0];
    for (let offset = 0; offset <= record.length; offset++) {
        for (const end of [false, true]) {
            const point = f.model.point(record, offset, end), logical = f.model.logicalPoint(point.node, point.offset);
            assert.equal(logical.node, record); assert.equal(logical.offset, offset);
            assert.equal(f.model.original(point.node), record);
        }
    }
    const join = record.fragments[0].end;
    assert.equal(f.model.point(record, join, false).node, record.fragments[1].node);
    assert.equal(f.model.point(record, join, false).offset, 0);
    assert.equal(f.model.point(record, join, true).node, record.fragments[0].node);
    assert.equal(f.model.point(record, join, true).offset, join);
    assert.equal(f.model.endpoint, f.model.point); assert.equal(f.model.logicalEndpoint, f.model.logicalPoint);
});

test('logical ranges retain exact strings across every fragment boundary and empty ranges', () => {
    const f = fixture(() => ['A漢\u{20000}\u{E0100}B文C']);
    const record = f.model.nodes[0];
    for (let start = 0; start <= record.length; start++) {
        for (let end = start; end <= record.length; end++) {
            assert.equal(f.model.range(record, start, end).toString(), record.data.slice(start, end));
        }
    }
});

test('generated-span and link element endpoints retain exact logical offsets', () => {
    const f = fixture(({element}) => [element('P', 'prefix中文suffix', element('A', 'link链接tail'), element('BR'), 'after后')]);
    const [first, linkRecord, last] = f.model.annotationNodes, [firstSpan, linkSpan] = f.wrappers();
    for (const [span, record, start, end] of [[firstSpan, first, 6, 8], [linkSpan, linkRecord, 4, 6]]) {
        const a = f.model.logicalPoint(span, 0, false), b = f.model.logicalPoint(span, 1, true);
        assert.equal(a.node, record); assert.equal(a.offset, start);
        assert.equal(b.node, record); assert.equal(b.offset, end);
    }
    const link = linkRecord.parentElement;
    assert.equal(f.model.logicalPoint(link, 1, false).offset, 4);
    assert.equal(f.model.logicalPoint(link, 2, true).offset, 6);
    const br = link.nextSibling;
    assert.equal(f.model.logicalPoint(br, 0, false).node, last);
    assert.equal(f.model.logicalPoint(br, 0, false).offset, 0);
    assert.equal(f.model.logicalPoint(br, 0, true).node, linkRecord);
    assert.equal(f.model.logicalPoint(br, 0, true).offset, linkRecord.length);
    assert.equal(f.model.logicalPoint(f.root, f.root.childNodes.length, false).node, last);
    assert.equal(f.model.logicalPoint(f.root, 0, true).node, first);
    assert.equal(f.model.logicalPoint(f.element('DIV', 'outside'), 0), null);
});

test('multi-record native range maps unchanged annotation source lengths and intersections', () => {
    const f = fixture(({element}) => [element('P', 'A中文B', element('B', 'bold粗体'), 'C')]);
    const records = f.model.annotationNodes;
    assert.deepEqual(Array.from(records, n => n.length), f.originals.map(n => n.data.length));
    const start = f.model.point(records[0], 1, false), end = f.model.point(records[1], 5, true);
    const range = f.document.createRange(); range.setStart(start.node, start.offset); range.setEnd(end.node, end.offset);
    assert.equal(range.toString(), '中文Bbold粗');
    assert.equal(f.model.intersects(range, records[0]), true);
    assert.equal(f.model.intersects(range, records[1]), true);
    assert.equal(f.model.intersects(range, records[2]), false);
});

test('empty original nodes and malformed surrogate text remain exact', () => {
    const f = fixture(({element}) => [element('P', '', 'A\uD800漢\uDC00Z', '\t')]);
    assert.deepEqual(data(f.model.nodes), ['', 'A\uD800漢\uDC00Z', '\t']);
    assert.equal(f.root.textContent, 'A\uD800漢\uDC00Z\t');
    const empty = f.model.nodes[0];
    assert.equal(f.model.point(empty, 0).offset, 0); assert.equal(f.model.range(empty, 0, 0).toString(), '');
});

test('invalid mappings fail safely and independent roots have independent models', () => {
    const f = fixture(() => ['A中文B']), record = f.model.nodes[0];
    for (const offset of [-1, .5, NaN, Infinity, record.length + 1, '1']) {
        assert.equal(f.model.point(record, offset), null);
        assert.equal(f.model.logicalPoint(record.fragments[0].node, offset), null);
    }
    assert.equal(f.model.logicalPoint(f.text('foreign'), 0), null);
    assert.equal(f.model.original(f.text('foreign')), null);
    assert.equal(f.model.range(record, 3, 1), null);
    assert.equal(f.context.InkReadScriptFonts.install(null), null);
    const second = f.element('MAIN', '第二');
    assert.notEqual(f.context.InkReadScriptFonts.install(second), f.model);
});

test('actual annotation runtime retains legacy fingerprint and original endpoints across mixed links', () => {
    const build = ({element}) => [element('P', 'prefix中文 A', element('A', 'link链接😀'), ' tail尾声'), '\n\t', element('P', 'last尾')];
    const legacy = withAnnotations(fixture(build, {install:false})), modern = withAnnotations(fixture(build));
    legacy.selectOriginal(0, 3, 2, 7); modern.selectOriginal(0, 3, 2, 7);
    assert.deepEqual(modern.capture(), legacy.capture());
    const old = legacy.capture();
    modern.api.setAnnotations([{id:'mixed', anchor:old}], 'test'); modern.flush();
    assert.deepEqual(modern.statuses(), [{id:'mixed', status:'attached'}]);
    assert.equal(typeof modern.api.pageForAnnotation('mixed', 'test'), 'number');
    assert.equal(modern.api.beginResize('mixed', 'resize-token', 'test'), true);
    assert.equal(modern.selection.toString(), old.rangeQuote);
    assert.deepEqual(JSON.parse(modern.api.captureResize('mixed', 'resize-token', 'test')), old);
    const physical = modern.allText();
    for (let i = 0; i < 10; i++) modern.context.InkReadScriptFonts.install(modern.root);
    assert.deepEqual(modern.allText(), physical);
    assert.deepEqual(modern.capture(), old);
});

test('actual annotation runtime attaches ambiguous legacy duplicate quotes by unchanged fast path', () => {
    const value = 'duplicate中文 '.repeat(12), start = value.indexOf('duplicate', 20);
    const build = ({element}) => [element('P', value)];
    const legacy = withAnnotations(fixture(build, {install:false})), modern = withAnnotations(fixture(build));
    legacy.selectOriginal(0, start, 0, start + 9); modern.selectOriginal(0, start, 0, start + 9);
    assert.deepEqual(modern.capture(), legacy.capture());
    const anchor = {...legacy.capture(), prefix:'', suffix:''};
    modern.api.setAnnotations([{id:'duplicate', anchor}], 'test'); modern.flush();
    assert.deepEqual(modern.statuses(), [{id:'duplicate', status:'attached'}]);
    modern.api.beginResize('duplicate', 'token', 'test');
    assert.equal(modern.capture().start, start);
    assert.equal(modern.capture().rangeQuote, 'duplicate');
    modern.api.setAnnotations([{id:'duplicate', anchor:{...anchor, documentFingerprint:'changed'}}], 'test'); modern.flush();
    assert.deepEqual(modern.statuses(), [{id:'duplicate', status:'unmatched'}]);
});

test('actual annotation runtime captures generated-span and partial-link element endpoints exactly', () => {
    const f = withAnnotations(fixture(({element}) => [element('P', 'prefix中文suffix', element('A', 'link链接tail'))]));
    const first = f.wrappers()[0], range = f.document.createRange(); range.selectNodeContents(first);
    f.selectRange(range);
    assert.equal(f.capture().rangeQuote, '中文'); assert.equal(f.capture().startOffset, 6); assert.equal(f.capture().endOffset, 8);
    const link = f.model.nodes[1].parentElement, across = f.document.createRange();
    across.setStart(first, 0); across.setEnd(link, 2); f.selectRange(across);
    assert.equal(f.capture().rangeQuote, '中文suffixlink链接');
    assert.equal(f.capture().startNode, 0); assert.equal(f.capture().startOffset, 6);
    assert.equal(f.capture().endNode, 1); assert.equal(f.capture().endOffset, 6);
});
