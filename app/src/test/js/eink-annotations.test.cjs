// Deterministic DOM/Range tests. Native WebView selection remains a device test.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const runtime = fs.readFileSync(path.join(__dirname, '../../main/assets/eink-annotations.js'), 'utf8');

function text(data) { return {nodeType:3, data, get length() {return this.data.length;}}; }
function element(tagName, ...children) {
    const listeners = new Map();
    const e = {nodeType:1, tagName, childNodes:[], style:{}, attributes:{}, firstChildReads:0, classList:{contains:()=>false},
        get firstChild() {this.firstChildReads++;return this.childNodes[0] || null;},
        appendChild(node) {
            if(this.childNodes.length) this.childNodes.at(-1).nextSibling=node;
            node.nextSibling=null;node.parentElement=this;this.childNodes.push(node);return node;
        },
        removeChild(node) {let i=this.childNodes.indexOf(node);this.childNodes.splice(i,1);if(i>0)this.childNodes[i-1].nextSibling=this.childNodes[i]||null;},
        remove() {this.parentElement?.removeChild(this);},
        setAttribute(key,value) {this.attributes[key]=value;},
        addEventListener:(name,fn)=>listeners.set(name,fn),removeEventListener:name=>listeners.delete(name),listeners};
    children.forEach(c=>e.appendChild(typeof c==='string'?text(c):c));return e;
}
function allText(root) {return root.nodeType===3?[root]:root.childNodes.flatMap(allText);}
function fixture(children, {custom=false, generation='doc-1', captureBudget=null}={}) {
    const metadata=element('DIV',element('H1','Header not article'),element('P','Byline not article'));
    metadata.classList={contains:name=>name==='eink-metadata'};
    const frame=element('MAIN',metadata,...children),head=element('HEAD'),body=element('BODY',frame),html=element('HTML',head,body);
    const documentListeners=new Map(),documentListenerOptions=new Map(),windowListeners=new Map(),fontListeners=new Map(),queue=new Map();
    const calls=[],observers=[],highlights=new Map();let sequence=0,selection=null,scale=1,rafRequests=0;
    const nodes=allText(frame), original=nodes.map(n=>({node:n,data:n.data,parent:n.parentElement}));
    // Any attempt to mutate/split indexed article text fails the test immediately.
    nodes.forEach(n=>{const value=n.data;Object.defineProperty(n,'data',{get:()=>value,set:()=>{throw Error('Article text mutated');}});});
    function geometry(node,offset) {
        let absolute=offset;for(const n of nodes) {if(n===node)break;absolute+=n.length;}
        const glyph=8*scale, chars=40, rows=20, col=Math.floor(absolute/(chars*rows));
        const left=20+col*400+(absolute%chars)*glyph-context._currentPage*400, top=20+(Math.floor(absolute/chars)%rows)*20*scale;
        return {left,top,width:glyph,height:20*scale,right:left+glyph,bottom:top+20*scale};
    }
    function createRange() {return {startContainer:null,endContainer:null,startOffset:0,endOffset:0,
        setStart(n,o){this.startContainer=n;this.startOffset=o;},setEnd(n,o){this.endContainer=n;this.endOffset=o;},
        intersectsNode(n){return nodes.indexOf(n)>=nodes.indexOf(this.startContainer)&&nodes.indexOf(n)<=nodes.indexOf(this.endContainer);},
        getClientRects(){return [geometry(this.startContainer,this.startOffset),geometry(this.endContainer,Math.max(0,this.endOffset-1))];}};}
    const document={readyState:'complete',head,body,documentElement:html,fonts:{addEventListener:(n,f)=>fontListeners.set(n,f),removeEventListener:n=>fontListeners.delete(n)},
        getElementById:id=>id==='eink-content'?frame:null,createElement:tag=>element(tag.toUpperCase()),createRange,
        addEventListener:(n,f,options)=>{documentListeners.set(n,f);documentListenerOptions.set(n,options);},
        removeEventListener:n=>{documentListeners.delete(n);documentListenerOptions.delete(n);}};
    const context={console,Math,document,innerWidth:400,innerHeight:440,_inkreadGeneration:generation,_pageStride:400,_currentPage:0,
        InkReadFormatting:{effectiveMargins:()=>({left:20,right:20,top:20,bottom:20})},
        Android:{onAnnotationLocations:(...args)=>calls.push(args)},
        getSelection:()=>selection,
        requestAnimationFrame:fn=>{rafRequests++;queue.set(++sequence,fn);return sequence;},cancelAnimationFrame:id=>queue.delete(id),
        addEventListener:(n,f)=>windowListeners.set(n,f),removeEventListener:n=>windowListeners.delete(n),
        MutationObserver:class {constructor(fn){this.fn=fn;observers.push(this);}observe(target,opts){this.target=target;this.opts=opts;}disconnect(){this.disconnected=true;}}};
    if(custom){context.CSS={highlights};context.Highlight=class extends Set {};}
    context.window=context;vm.createContext(context);
    const source=captureBudget===null?runtime:runtime.replace('CAPTURE_BYTE_LIMIT = 128 * 1024',`CAPTURE_BYTE_LIMIT = ${captureBudget}`);
    vm.runInContext(source,context);
    function flush(){while(queue.size){const q=[...queue.values()];queue.clear();q.forEach(fn=>fn());}}
    function select(a,start,b,end,{backwards=false,displayText=null}={}) {
        const range=createRange();range.setStart(a,start);range.setEnd(b,end);
        let raw='';
        for(let i=nodes.indexOf(a);i<=nodes.indexOf(b);i++) raw+=nodes[i].data.slice(i===nodes.indexOf(a)?start:0,i===nodes.indexOf(b)?end:nodes[i].length);
        range.toString=()=>raw;
        selection={rangeCount:1,isCollapsed:a===b&&start===end,getRangeAt:()=>range,toString:()=>displayText===null?raw:displayText,
            anchorNode:backwards?b:a,anchorOffset:backwards?end:start,focusNode:backwards?a:b,focusOffset:backwards?start:end,
            removeAllRanges(){this.rangeCount=0;this.isCollapsed=true;}};
    }
    const api=context.InkReadAnnotations;
    function capture(){return JSON.parse(api.captureSelection(generation));}
    function set(values){const result=api.setAnnotations(values,generation);flush();return result;}
    function statuses(){return JSON.parse(JSON.stringify(api.locationStatus()));}
    function immutable(){assert.deepEqual(allText(frame),nodes);original.forEach(o=>{assert.equal(o.node.data,o.data);assert.equal(o.node.parentElement,o.parent);});}
    api.setSelectionMode(true,generation);
    function traversalReads(root=frame){return (root.firstChildReads||0)+(root.childNodes||[]).reduce((sum,n)=>sum+traversalReads(n),0);}
    return {api,context,frame,body,head,html,nodes,select,capture,set,statuses,flush,immutable,calls,highlights,observers,documentListeners,documentListenerOptions,windowListeners,fontListeners,
        traversalReads,rafRequests:()=>rafRequests,setScale:s=>scale=s};
}

test('body-only UTF-16 quote and endpoints include inline formatting, CJK and whole emoji',()=>{
    const a=text('  Hello '),b=text('世界😀'),c=text(' end.  ');
    const f=fixture([element('P',a,element('B',b),c)]);f.select(a,2,c,5);
    const saved=f.capture();assert.equal(saved.quote,'Hello 世界😀 end.');assert.equal(saved.start,2);assert.equal(saved.end,2+saved.quote.length);
    assert.equal(saved.startNode,0);assert.equal(saved.startOffset,2);assert.equal(saved.endNode,2);assert.equal(saved.endOffset,5);
    assert.equal(saved.version,1);assert.equal(saved.textMapVersion,1);assert.equal(saved.prefix,'  ');assert.equal(saved.suffix,'  ');
    f.set([{id:'one',anchor:saved}]);assert.deepEqual(f.statuses(),[{id:'one',status:'attached'}]);f.immutable();
});

test('whitespace-only nodes and cross paragraphs preserve exact Range.toString semantics, not visual line breaks',()=>{
    const a=text('First'),b=text('bold'),white=text('\n \t'),c=text('next'),d=text('line');
    const f=fixture([element('P',a,element('EM',b)),white,element('P',c,element('BR'),d)]);
    f.select(a,0,d,4,{backwards:true});const saved=f.capture();
    assert.equal(saved.quote,'Firstbold\n \tnextline');f.set([{id:'cross',anchor:saved}]);assert.equal(f.statuses()[0].status,'attached');
    f.select(white,0,d,4);assert.equal(f.capture().quote,'\n \tnextline');f.immutable();
});

test('reversed selection has the same document-ordered anchor; partial surrogate is rejected',()=>{
    const a=text('A😀漢字Z'),f=fixture([element('P',a)]);
    f.select(a,1,a,5);const forward=f.capture();f.select(a,1,a,5,{backwards:true});assert.deepEqual(f.capture(),forward);
    assert.equal(forward.quote,'😀漢字');f.select(a,2,a,5);assert.equal(f.api.captureSelection('doc-1'),null);
});

test('capture requires active selection mode, live document generation and nonempty range',()=>{
    const a=text('text'),f=fixture([element('P',a)]);f.select(a,0,a,4);
    assert.equal(f.api.captureSelection('old-doc'),null);f.api.setSelectionMode(false,'doc-1');assert.equal(f.capture(),null);
    f.api.setSelectionMode(true,'doc-1');f.select(a,2,a,2);assert.equal(f.capture(),null);
    assert.equal(f.api.setAnnotations([], 'old-doc'),false);f.windowListeners.get('pagehide')();assert.equal(f.api.setSelectionMode(true,'doc-1'),false);
});

test('state-only selection mode sync disables capture without mutating the native Copy range',()=>{
    const a=text('Native Copy keeps its exact quote'),f=fixture([element('P',a)]);f.select(a,7,a,11);
    const selection=f.context.getSelection(),range=selection.getRangeAt(0),saved=f.capture();
    let clears=0;const remove=selection.removeAllRanges;
    selection.removeAllRanges=function(){clears++;return remove.call(this);};
    // Both the native completion and its later reactive sync are state-only.
    for(let repeat=0;repeat<2;repeat++) {
        assert.equal(f.api.setSelectionMode(false,'doc-1',false),true);
        assert.equal(f.api.captureSelection('doc-1'),null);
        assert.equal(f.context.getSelection(),selection);assert.equal(selection.getRangeAt(0),range);
        assert.equal(selection.rangeCount,1);assert.equal(selection.isCollapsed,false);
        assert.equal(range.startContainer,a);assert.equal(range.startOffset,7);
        assert.equal(range.endContainer,a);assert.equal(range.endOffset,11);
        assert.equal(selection.toString(),'Copy');assert.equal(clears,0);
    }
    assert.equal(f.api.setSelectionMode(true,'doc-1',false),true);
    assert.deepEqual(f.capture(),saved);assert.equal(clears,0);f.immutable();
});

test('explicit selection dismissal still clears by default and with clearRange true',()=>{
    const a=text('Explicit dismissal'),f=fixture([element('P',a)]);
    for(const explicitFlag of [false,true]) {
        f.api.setSelectionMode(true,'doc-1');f.select(a,0,a,8);
        const selection=f.context.getSelection();let clears=0;const remove=selection.removeAllRanges;
        selection.removeAllRanges=function(){clears++;return remove.call(this);};
        const result=explicitFlag?f.api.setSelectionMode(false,'doc-1',true):f.api.setSelectionMode(false,'doc-1');
        assert.equal(result,true);assert.equal(clears,1);
        assert.equal(selection.rangeCount,0);assert.equal(selection.isCollapsed,true);
        f.select(a,0,a,8);assert.equal(f.api.captureSelection('doc-1'),null,'Dismissal also disables capture');
    }
    f.immutable();
});

test('stale generation mode sync cannot clear a range or change capture enablement',()=>{
    const a=text('Current document quote'),f=fixture([element('P',a)]);f.select(a,0,a,7);
    const selection=f.context.getSelection(),range=selection.getRangeAt(0),saved=f.capture();
    let clears=0;const remove=selection.removeAllRanges;
    selection.removeAllRanges=function(){clears++;return remove.call(this);};
    assert.equal(f.api.setSelectionMode(false,'old-doc'),false);
    assert.equal(f.api.setSelectionMode(false,'old-doc',false),false);
    assert.deepEqual(f.capture(),saved,'Stale disable must not change live capture enablement');
    assert.equal(f.api.setSelectionMode(false,'doc-1',false),true);
    assert.equal(f.api.setSelectionMode(true,'old-doc',false),false);
    assert.equal(f.api.captureSelection('doc-1'),null,'Stale enable must not revive disabled capture');
    assert.equal(f.context.getSelection(),selection);assert.equal(selection.getRangeAt(0),range);
    assert.equal(selection.rangeCount,1);assert.equal(selection.isCollapsed,false);
    assert.equal(selection.toString(),'Current');assert.equal(clears,0);f.immutable();
});

test('only an idle single-pointer BODY or HTML margin start cancels native default selection',()=>{
    const a=text('Selectable article'),link=element('A','Selectable link'),img=element('IMG'),p=element('P',a);
    const f=fixture([p,link,img]);f.api.setSelectionMode(false,'doc-1',false);
    const handler=f.documentListeners.get('touchstart');
    assert.equal(typeof handler,'function');assert.equal(f.documentListenerOptions.get('touchstart').passive,false);
    function cancelled(target,extra={}) {let count=0;handler({target,cancelable:true,touches:[{}],preventDefault(){count++;},...extra});return count;}
    assert.equal(cancelled(f.body),1);assert.equal(cancelled(f.html),1);
    for(const target of [f.frame,p,a,link,img,f.frame.childNodes[0]]) assert.equal(cancelled(target),0);
    assert.equal(cancelled(f.body,{touches:[]}),0);assert.equal(cancelled(f.body,{touches:[{},{}]}),0);
    assert.equal(cancelled(f.body,{cancelable:false}),0);
    f.immutable();
});

test('margin guard never interferes with selection mode, native range or pending Copy range',()=>{
    const a=text('Keep native handles and Copy'),f=fixture([element('P',a)]);
    const handler=f.documentListeners.get('touchstart');let cancelled=0;
    const event={target:f.body,cancelable:true,touches:[{}],preventDefault(){cancelled++;}};
    handler(event);assert.equal(cancelled,0,'Explicit selection mode stays native even before it has a range');
    f.select(a,5,a,11);const selection=f.context.getSelection(),range=selection.getRangeAt(0);
    let clears=0;const remove=selection.removeAllRanges;
    selection.removeAllRanges=function(){clears++;return remove.call(this);};
    f.api.setSelectionMode(false,'doc-1',false);handler(event);
    assert.equal(cancelled,0);assert.equal(clears,0);assert.equal(selection.getRangeAt(0),range);
    assert.equal(selection.toString(),'native');assert.equal(selection.isCollapsed,false);f.immutable();
});

test('pagehide removes the margin guard and an old callback cannot affect another document',()=>{
    const f=fixture([element('P','Article')]);f.api.setSelectionMode(false,'doc-1',false);
    const handler=f.documentListeners.get('touchstart');let cancelled=0;
    const event={target:f.body,cancelable:true,touches:[{}],preventDefault(){cancelled++;}};
    f.windowListeners.get('pagehide')();
    assert.equal(f.documentListeners.has('touchstart'),false);
    handler(event);assert.equal(cancelled,0);f.immutable();
});

test('captured JSON survives clearing selection before a note editor opens',()=>{
    const a=text('An exact note quote'),f=fixture([element('P',a)]);f.select(a,3,a,13);
    const json=f.api.captureSelection('doc-1');f.api.clearSelection();assert.equal(f.capture(),null);assert.equal(JSON.parse(json).quote,'exact note');
});

test('changed body recovers only a unique exact quote plus context',()=>{
    const a=text('Before unique quotation after.'),f=fixture([element('P',a)]);f.select(a,7,a,23);const saved=f.capture();
    const g=fixture([element('P','New paragraph.'),element('P',a.data)]);
    g.set([{id:'shifted',anchor:saved}]);assert.equal(g.statuses()[0].status,'attached');
    const h=fixture([element('P','Before different quotation after.')]);h.set([{id:'missing',anchor:saved}]);assert.equal(h.statuses()[0].status,'unmatched');
});

test('duplicate quotes without sufficient context stay unmatched, context disambiguates',()=>{
    const a=text('one repeat two repeat three'),f=fixture([element('P',a)]);f.select(a,4,a,10);const saved=f.capture();
    f.set([{id:'ambiguous',anchor:{...saved,documentFingerprint:'changed',prefix:'',suffix:''}}]);assert.equal(f.statuses()[0].status,'unmatched');
    f.set([{id:'context',anchor:{...saved,documentFingerprint:'changed'}}]);assert.equal(f.statuses()[0].status,'attached');
});

test('same fingerprint still requires valid offsets, quote and endpoints; no false attachment',()=>{
    const a=text('repeat repeat'),f=fixture([element('P',a)]);f.select(a,0,a,6);const saved=f.capture();
    f.set([{id:'invalid',anchor:{...saved,start:7,end:13,startOffset:7,endOffset:13,prefix:'wrong',suffix:'wrong'}}]);assert.equal(f.statuses()[0].status,'unmatched');
    f.set([{id:'bad-version',anchor:{...saved,textMapVersion:2}}]);assert.equal(f.statuses()[0].status,'unmatched');
});

for(const custom of [false,true]) test(`${custom?'CSS Custom Highlight':'rectangle fallback'} rendering preserves original DOM and redraws on reflow`,()=>{
    const a=text('Immutable article quote'),f=fixture([element('P',a)],{custom});f.select(a,0,a,9);const saved=f.capture();
    f.set([{id:'mark',anchor:saved}]);f.immutable();
    if(custom) assert.equal(f.highlights.get('inkread-annotation').size,1);
    else {assert.equal(f.body.childNodes.length,2);assert.ok(f.body.childNodes[1].childNodes.length>0);assert.match(f.body.childNodes[1].style.cssText,/position:fixed/);}
    for(const event of [()=>f.frame.listeners.get('load')(),()=>f.fontListeners.get('loadingdone')(),()=>f.documentListeners.get('selectionchange')(),()=>f.windowListeners.get('resize')(),()=>f.observers[0].fn()]) {f.setScale(1.2);event();f.flush();f.immutable();}
    f.set([]);if(custom)assert.equal(f.highlights.has('inkread-annotation'),false);else assert.equal(f.body.childNodes.length,1);
    f.windowListeners.get('pagehide')();f.immutable();assert.ok(f.observers[0].disconnected);
});

test('offscreen matches remain attached and return a page without issuing navigation commands',()=>{
    const a=text('x'.repeat(1600)+' target quote'),f=fixture([element('P',a)]);f.select(a,1601,a,a.length);const saved=f.capture();
    f.set([{id:'far',anchor:saved}]);assert.equal(f.statuses()[0].status,'attached');assert.equal(f.api.pageForAnnotation('far','doc-1'),2);
    assert.equal(f.context._currentPage,0);assert.equal(f.api.pageForAnnotation('missing','doc-1'),null);assert.equal(f.api.pageForAnnotation('far','old'),null);
});


test('selection edges, repeated spaces, tabs, newlines and combining marks are never normalized',()=>{
    const a=text('  poetry  line\n\tCafe\u0301 😀  '),f=fixture([element('PRE',a)]);
    f.select(a,0,a,a.length);const saved=f.capture();
    assert.equal(saved.quote,a.data);assert.equal(saved.rangeQuote,a.data);assert.equal(saved.start,0);assert.equal(saved.end,a.length);
    f.set([{id:'exact-whitespace',anchor:saved}]);assert.equal(f.statuses()[0].status,'attached');
    const changed=fixture([element('PRE',a.data.replace('poetry  line','poetry line'))]);
    changed.set([{id:'changed-whitespace',anchor:saved}]);assert.equal(changed.statuses()[0].status,'unmatched');
});


test('browser display quote and raw range quote preserve their distinct paragraph whitespace exactly',()=>{
    const a=text('First paragraph.'),b=text('Next paragraph.'),f=fixture([element('P',a),element('P',b)]);
    f.select(a,0,b,b.length,{displayText:'First paragraph.\n\nNext paragraph.'});const saved=f.capture();
    assert.equal(saved.quote,'First paragraph.\n\nNext paragraph.');
    assert.equal(saved.rangeQuote,'First paragraph.Next paragraph.');
    assert.equal(saved.end,saved.rangeQuote.length);
    f.set([{id:'paragraphs',anchor:saved}]);assert.equal(f.statuses()[0].status,'attached');
    f.set([{id:'mismatched-raw',anchor:{...saved,rangeQuote:'invented whitespace'}}]);assert.equal(f.statuses()[0].status,'unmatched');
});


test('a present but failing Custom Highlight API uses the actual rectangle fallback',()=>{
    const a=text('Fallback text'),f=fixture([element('P',a)],{custom:true});
    f.context.Highlight=function(){throw Error('unsupported');};f.select(a,0,a,a.length);
    f.set([{id:'fallback',anchor:f.capture()}]);
    assert.equal(f.statuses()[0].status,'attached');assert.equal(f.body.childNodes.length,2);
    assert.ok(f.body.childNodes[1].childNodes.length>0);f.immutable();
});


test('empty readers do not traverse body text or schedule redraws until annotations or selection need a map',()=>{
    const a=text('Article not yet annotated'),f=fixture([element('P',a)]);
    assert.equal(f.traversalReads(),0);assert.equal(f.rafRequests(),0);
    f.set([]);f.api.clearSelection();
    for(const trigger of [()=>f.frame.listeners.get('load')(),()=>f.fontListeners.get('loadingdone')(),
        ()=>f.documentListeners.get('selectionchange')(),()=>f.windowListeners.get('resize')(),()=>f.observers[0].fn()]) trigger();
    f.flush();assert.equal(f.traversalReads(),0);assert.equal(f.rafRequests(),0);assert.equal(f.body.childNodes.length,1);
    assert.equal(f.api.captureSelection('doc-1'),null);assert.equal(f.traversalReads(),0);
    f.select(a,0,a,a.length);const saved=f.capture();assert.ok(f.traversalReads()>0);
    const traversals=f.traversalReads();f.set([{id:'first',anchor:saved}]);f.capture();
    assert.equal(f.traversalReads(),traversals,'The immutable map is cached for this document');
    f.set([]);const frames=f.rafRequests();f.observers[0].fn();f.documentListeners.get('selectionchange')();f.flush();
    assert.equal(f.rafRequests(),frames,'No frames after clearing the last annotation');assert.equal(f.body.childNodes.length,1);
    const g=fixture([element('P',a.data)]);assert.equal(g.traversalReads(),0);
    g.set([{id:'reopened',anchor:saved}]);assert.ok(g.traversalReads()>0);assert.equal(g.statuses()[0].status,'attached');
});


test('selection spanning metadata is rejected instead of exporting text outside its body anchor',()=>{
    const a=text('Body text'),f=fixture([element('P',a)]);
    f.select(f.nodes[0],0,a,a.length,{displayText:'Header not article\nBody text'});
    assert.equal(f.capture(),null);f.immutable();
});


for(const value of ['中文漢字', '😀🧑🏽‍💻', '\u0000\u0001\t\n\r"\\字😀']) {
    test(`capture budget counts exact serialized UTF-8 bytes at the boundary: ${JSON.stringify(value)}`,()=>{
        function captureWithBudget(budget=null) {
            const node=text(value),f=fixture([element('P',node)],{captureBudget:budget});f.select(node,0,node,node.length);
            return f.api.captureSelection('doc-1');
        }
        const original=captureWithBudget(), bytes=Buffer.byteLength(original,'utf8');
        assert.equal(captureWithBudget(bytes),original,'Exactly at the byte limit is valid and unmodified');
        assert.deepEqual(JSON.parse(captureWithBudget(bytes-1)),{error:'selection_too_large'});
        assert.equal(JSON.parse(original).rangeQuote,value);
        assert.equal(JSON.parse(original).quote,value);
    });
}

test('production 128 KiB limit rejects oversized selection with a small explicit envelope, without truncation',()=>{
    const node=text('中文😀\u0000'.repeat(12000)),f=fixture([element('PRE',node)]);
    f.select(node,0,node,node.length);const result=f.api.captureSelection('doc-1');
    assert.deepEqual(JSON.parse(result),{error:'selection_too_large'});
    assert.ok(Buffer.byteLength(result,'utf8')<128);
    assert.equal(node.data,'中文😀\u0000'.repeat(12000));f.immutable();
    // A shorter selection in the same cached document still works unchanged.
    f.select(node,0,node,5);const next=f.capture();
    assert.equal(next.quote,'中文😀\u0000');assert.equal(next.rangeQuote,next.quote);
});
