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
        getAttribute(key) {return this.attributes[key]??null;},
        hasAttribute(key) {return Object.hasOwn(this.attributes,key);},
        addEventListener:(name,fn)=>listeners.set(name,fn),removeEventListener:name=>listeners.delete(name),listeners};
    children.forEach(c=>e.appendChild(typeof c==='string'?text(c):c));return e;
}
function allText(root) {return root.nodeType===3?[root]:root.childNodes.flatMap(allText);}
function fixture(children, {custom=false, generation='doc-1', captureBudget=null, selectionMode=true}={}) {
    const metadata=element('DIV',element('H1','Header not article'),element('P','Byline not article'));
    metadata.classList={contains:name=>name==='eink-metadata'};
    const frame=element('MAIN',metadata,...children),head=element('HEAD'),body=element('BODY',frame),html=element('HTML',head,body);
    const documentListeners=new Map(),documentListenerOptions=new Map(),windowListeners=new Map(),fontListeners=new Map(),queue=new Map();
    const calls=[],autoCalls=[],observers=[],highlights=new Map();let sequence=0,scale=1,rafRequests=0,hitTarget=frame,rectProvider=null;
    const nodes=allText(frame), original=nodes.map(n=>({node:n,data:n.data,parent:n.parentElement}));
    // Any attempt to mutate/split indexed article text fails the test immediately.
    nodes.forEach(n=>{const value=n.data;Object.defineProperty(n,'data',{get:()=>value,set:()=>{throw Error('Article text mutated');}});});
    function geometry(node,offset) {
        let absolute=offset;for(const n of nodes) {if(n===node)break;absolute+=n.length;}
        const glyph=8*scale, chars=40, rows=20, col=Math.floor(absolute/(chars*rows));
        const left=20+col*400+(absolute%chars)*glyph-context._currentPage*400, top=20+(Math.floor(absolute/chars)%rows)*20*scale;
        return {left,top,width:glyph,height:20*scale,right:left+glyph,bottom:top+20*scale};
    }
    function rangeText(range) {
        const start=nodes.indexOf(range.startContainer),end=nodes.indexOf(range.endContainer);let raw='';
        for(let i=start;i>=0&&i<=end;i++) raw+=nodes[i].data.slice(i===start?range.startOffset:0,i===end?range.endOffset:nodes[i].length);
        return raw;
    }
    function createRange() {return {startContainer:null,endContainer:null,startOffset:0,endOffset:0,
        setStart(n,o){this.startContainer=n;this.startOffset=o;},setEnd(n,o){this.endContainer=n;this.endOffset=o;},
        intersectsNode(n){return nodes.indexOf(n)>=nodes.indexOf(this.startContainer)&&nodes.indexOf(n)<=nodes.indexOf(this.endContainer);},
        toString(){return rangeText(this);},
        getClientRects(){return rectProvider?rectProvider(this):[geometry(this.startContainer,this.startOffset),geometry(this.endContainer,Math.max(0,this.endOffset-1))];}};}
    function makeSelection(range=null,{backwards=false,displayText=null}={}) {
        let current=range;
        return {get rangeCount(){return current?1:0;},get isCollapsed(){return !current||(current.startContainer===current.endContainer&&current.startOffset===current.endOffset);},
            getRangeAt:()=>current,toString:()=>!current?'':displayText===null?current.toString():displayText,
            get anchorNode(){return current&&(backwards?current.endContainer:current.startContainer);},
            get anchorOffset(){return current&&(backwards?current.endOffset:current.startOffset);},
            get focusNode(){return current&&(backwards?current.startContainer:current.endContainer);},
            get focusOffset(){return current&&(backwards?current.startOffset:current.endOffset);},
            removeAllRanges(){current=null;},addRange(next){current=next;displayText=null;backwards=false;}};
    }
    let selection=makeSelection();
    const document={readyState:'complete',head,body,documentElement:html,fonts:{addEventListener:(n,f)=>fontListeners.set(n,f),removeEventListener:n=>fontListeners.delete(n)},
        getElementById:id=>id==='eink-content'?frame:null,createElement:tag=>element(tag.toUpperCase()),createRange,elementFromPoint:()=>hitTarget,
        addEventListener:(n,f,options)=>{documentListeners.set(n,f);documentListenerOptions.set(n,options);},
        removeEventListener:n=>{documentListeners.delete(n);documentListenerOptions.delete(n);}};
    const context={console,Math,document,innerWidth:400,innerHeight:440,_inkreadGeneration:generation,_pageStride:400,_currentPage:0,
        InkReadFormatting:{effectiveMargins:()=>({left:20,right:20,top:20,bottom:20})},
        Android:{onAnnotationLocations:(...args)=>calls.push(args),onAutoAnnotation:(...args)=>autoCalls.push(args)},
        getSelection:()=>selection,
        requestAnimationFrame:fn=>{rafRequests++;queue.set(++sequence,fn);return sequence;},cancelAnimationFrame:id=>queue.delete(id),
        addEventListener:(n,f)=>windowListeners.set(n,f),removeEventListener:n=>windowListeners.delete(n),
        MutationObserver:class {constructor(fn){this.fn=fn;observers.push(this);}observe(target,opts){this.target=target;this.opts=opts;}disconnect(){this.disconnected=true;}}};
    if(custom){context.CSS={highlights};context.Highlight=class extends Set {};}
    context.window=context;vm.createContext(context);
    const source=captureBudget===null?runtime:runtime.replace('CAPTURE_BYTE_LIMIT = 128 * 1024',`CAPTURE_BYTE_LIMIT = ${captureBudget}`);
    vm.runInContext(source,context);
    function tick(){const q=[...queue.values()];queue.clear();q.forEach(fn=>fn());}
    function flush(){let iterations=0;while(queue.size){assert.ok(iterations++<100,'Animation work must converge');tick();}}
    function select(a,start,b,end,{backwards=false,displayText=null}={}) {
        const range=createRange();range.setStart(a,start);range.setEnd(b,end);
        selection=makeSelection(range,{backwards,displayText});return range;
    }
    const api=context.InkReadAnnotations;
    function capture(){return JSON.parse(api.captureSelection(generation));}
    function set(values){const result=api.setAnnotations(values,generation);flush();return result;}
    function statuses(){return JSON.parse(JSON.stringify(api.locationStatus()));}
    function immutable(){assert.deepEqual(allText(frame),nodes);original.forEach(o=>{assert.equal(o.node.data,o.data);assert.equal(o.node.parentElement,o.parent);});}
    if(selectionMode) api.setSelectionMode(true,generation);
    function traversalReads(root=frame){return (root.firstChildReads||0)+(root.childNodes||[]).reduce((sum,n)=>sum+traversalReads(n),0);}
    return {api,context,frame,body,head,html,nodes,select,capture,set,statuses,flush,tick,immutable,calls,autoCalls,highlights,observers,documentListeners,documentListenerOptions,windowListeners,fontListeners,
        selectionChanged:()=>documentListeners.get('selectionchange')(),setHitTarget:target=>hitTarget=target,setRects:provider=>rectProvider=provider,
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

function target(f,x=105,y=105,generation='doc-1') {return JSON.parse(f.api.tapTarget(x,y,generation));}
function fixedRect(left=100,top=100,right=200,bottom=120) {return {left,top,right,bottom,width:right-left,height:bottom-top};}
function saveRange(f,node,start,end,id='highlight') {
    f.api.setSelectionMode(true,'doc-1');f.select(node,start,node,end);
    const annotation={id,anchor:f.capture()};f.api.clearSelection();return annotation;
}
function autoGesture(f,token='gesture-1') {
    assert.equal(f.api.beginSelectionGesture(token,'doc-1'),true);
    assert.equal(f.api.completeSelectionGesture(token,'doc-1'),true);
}

test('saved highlights are tappable with selection mode off by default, without changing article DOM',()=>{
    const source=text('Tap this saved quotation'),savedFixture=fixture([element('P',source)]);
    const saved=saveRange(savedFixture,source,4,14);
    for(const custom of [false,true]) {
        const node=text(source.data),f=fixture([element('P',node)],{custom,selectionMode:false});
        f.setRects(()=>[fixedRect()]);f.set([saved]);
        assert.deepEqual(target(f),{kind:'annotation',id:'highlight'});
        assert.deepEqual(target(f,201),{kind:'page'});
        f.select(node,4,node,14);assert.equal(f.capture(),null,'Hit testing does not enable capture');
        assert.deepEqual(target(f),{kind:'blocked'},'Native selection wins even with capture disabled');
        f.api.clearSelection();assert.deepEqual(target(f),{kind:'annotation',id:'highlight'});f.immutable();
    }
});

test('native link and button descendants win over annotations; selection has first priority',()=>{
    const node=text('Link and button'),inline=element('SPAN',node),link=element('A',inline),f=fixture([link]);
    f.setRects(()=>[fixedRect()]);const saved=saveRange(f,node,0,4);f.set([saved]);f.api.setSelectionMode(false,'doc-1');
    f.setHitTarget(inline);assert.deepEqual(target(f),{kind:'blocked'});
    const button=element('BUTTON',element('STRONG','button'));f.setHitTarget(button.firstChild);assert.deepEqual(target(f),{kind:'blocked'});
    const role=element('DIV',element('SPAN','custom'));role.setAttribute('role','button');f.setHitTarget(role.firstChild);assert.deepEqual(target(f),{kind:'blocked'});
    f.select(node,0,node,4);f.context.document.elementFromPoint=()=>{throw Error('Selection priority must avoid native hit testing');};
    assert.deepEqual(target(f),{kind:'blocked'});f.immutable();
});

test('overlap resolution uses shortest exact text span then lexical ID, independent of annotation order',()=>{
    const node=text('Overlapping ranges'),f=fixture([element('P',node)]);f.setRects(()=>[fixedRect()]);
    const wide=saveRange(f,node,0,18,'wide'),shortZ=saveRange(f,node,2,6,'z'),shortA={...shortZ,id:'a'};
    f.api.setSelectionMode(false,'doc-1');
    for(const values of [[wide,shortZ,shortA],[shortA,shortZ,wide]]) {
        f.set(values);assert.deepEqual(target(f),{kind:'annotation',id:'a'});
    }
    f.set([wide,{...shortA,anchor:{...shortA.anchor,rangeQuote:'missing'}}]);
    assert.deepEqual(target(f),{kind:'annotation',id:'wide'},'Unmatched anchors never win');f.immutable();
});

test('tap and selection geometry are live, clipped to effective margins and viewport, and generation guarded',()=>{
    const node=text('Geometry quote'),f=fixture([element('P',node)]),saved=saveRange(f,node,0,node.length);
    f.setRects(()=>[fixedRect(-20,-20,500,500)]);f.set([saved]);f.api.setSelectionMode(false,'doc-1');
    for(const [x,y] of [[19,100],[380,100],[100,19],[100,420],[-1,100],[401,100]]) assert.deepEqual(target(f,x,y),{kind:'page'});
    assert.deepEqual(target(f,20,20),{kind:'annotation',id:'highlight'});
    f.context.InkReadFormatting.effectiveMargins=()=>({left:110,right:0,top:110,bottom:0});
    assert.deepEqual(target(f),{kind:'page'});assert.deepEqual(target(f,110,110),{kind:'annotation',id:'highlight'});
    f.setRects(()=>[fixedRect(150,150,170,170)]);assert.deepEqual(target(f,110,110),{kind:'page'});
    f.select(node,0,node,3);assert.equal(f.api.selectionContainsPoint(155,155,'doc-1'),true);
    assert.equal(f.api.selectionContainsPoint(171,155,'doc-1'),false);
    assert.equal(f.api.selectionContainsPoint(155,155,'old-doc'),false);
    f.api.clearSelection();assert.deepEqual(target(f,155,155,'old-doc'),{kind:'blocked'});
    assert.deepEqual(target(f,NaN,155),{kind:'blocked'});
    f.context._inkreadGeneration='doc-2';assert.deepEqual(target(f,155,155),{kind:'blocked'});f.immutable();
});

test('resize restores the exact saved ID and range, captures only on explicit matching save, and never edits the saved anchor',()=>{
    const a=text('First inline '),b=text('世界😀 last'),f=fixture([element('P',a,element('B',b))]);
    f.select(a,6,b,4);const anchor=f.capture();f.set([{id:'resize-me',anchor}]);f.api.setSelectionMode(false,'doc-1');
    assert.equal(f.api.beginResize('resize-me','edit-token','doc-1'),true);
    const selection=f.context.getSelection(),range=selection.getRangeAt(0);
    assert.equal(range.startContainer,a);assert.equal(range.startOffset,6);assert.equal(range.endContainer,b);assert.equal(range.endOffset,4);
    assert.deepEqual(JSON.parse(f.api.captureResize('resize-me','edit-token','doc-1')),anchor);
    f.select(a,0,b,b.length,{backwards:true});f.selectionChanged();f.flush();
    assert.equal(f.autoCalls.length,0,'Handle movement is never a save');
    const captured=JSON.parse(f.api.captureResize('resize-me','edit-token','doc-1'));
    assert.equal(captured.quote,a.data+b.data);assert.equal(anchor.quote,'inline 世界😀');
    assert.equal(f.api.cancelResize('resize-me','edit-token','doc-1'),true);
    assert.equal(f.context.getSelection().isCollapsed,true);
    assert.equal(f.api.captureResize('resize-me','edit-token','doc-1'),null);
    assert.equal(f.api.cancelResize('resize-me','edit-token','doc-1'),false);f.immutable();
});

test('resize rejects stale tokens, wrong IDs, missing/ambiguous anchors and superseded documents without clearing current selection',()=>{
    const node=text('repeat repeat'),f=fixture([element('P',node)]),saved=saveRange(f,node,0,6,'one');
    f.set([saved,{...saved,id:'ambiguous',anchor:{...saved.anchor,documentFingerprint:'other',prefix:'',suffix:''}}]);
    assert.equal(f.api.beginResize('missing','token','doc-1'),false);
    assert.equal(f.api.beginResize('ambiguous','token','doc-1'),false);
    assert.equal(f.api.beginResize('one','','doc-1'),false);
    assert.equal(f.api.beginResize('one',2,'doc-1'),false);
    assert.equal(f.api.beginResize('one','old','old-doc'),false);
    assert.equal(f.api.beginResize('one','first','doc-1'),true);
    assert.equal(f.api.beginResize('one','second','doc-1'),true);
    const selected=f.context.getSelection().getRangeAt(0);
    for(const [id,token,generation] of [['other','second','doc-1'],['one','first','doc-1'],['one','second','old-doc']]) {
        assert.equal(f.api.captureResize(id,token,generation),null);assert.equal(f.api.cancelResize(id,token,generation),false);
        assert.equal(f.context.getSelection().getRangeAt(0),selected);
    }
    f.context._inkreadGeneration='doc-2';assert.equal(f.api.captureResize('one','second','doc-1'),null);
    assert.equal(f.api.cancelResize('one','second','doc-1'),false);f.immutable();
});

test('auto-highlight is off by default and never arms from selection changes, DOM touch, or an unmatched UP',()=>{
    const node=text('Select me'),f=fixture([element('P',node)]);f.select(node,0,node,6);
    assert.equal(f.api.beginSelectionGesture('off','doc-1'),false);assert.equal(f.api.completeSelectionGesture('off','doc-1'),false);
    f.selectionChanged();f.flush();assert.equal(f.autoCalls.length,0);
    assert.equal(f.api.setAutoHighlightEnabled(true,'old-doc'),false);assert.equal(f.api.setAutoHighlightEnabled(true,'doc-1'),true);
    f.documentListeners.get('touchstart')({target:node,cancelable:true,touches:[{}],preventDefault(){throw Error('Article touch must stay native');}});
    f.selectionChanged();f.flush();assert.equal(f.autoCalls.length,0);
    assert.equal(f.api.completeSelectionGesture('no-down','doc-1'),false);
    assert.equal(f.api.beginSelectionGesture('down','doc-1'),true);f.selectionChanged();f.flush();assert.equal(f.autoCalls.length,0,'No save before native UP');
    assert.equal(f.api.completeSelectionGesture('other','doc-1'),false);f.flush();assert.equal(f.autoCalls.length,0);
    assert.equal(f.api.completeSelectionGesture('down','old-doc'),false);
    assert.equal(f.api.completeSelectionGesture('down','doc-1'),true);
    f.tick();assert.equal(f.autoCalls.length,0,'First frame is not stable enough');f.tick();assert.equal(f.autoCalls.length,1);
    assert.deepEqual(f.autoCalls[0].slice(0,2),['doc-1','down']);assert.equal(JSON.parse(f.autoCalls[0][2]).quote,'Select');f.immutable();
});

test('auto capture restarts its two-frame stability check during range changes and emits only the final exact range',()=>{
    const node=text('A stable final quotation'),f=fixture([element('P',node)]);f.api.setAutoHighlightEnabled(true,'doc-1');
    assert.equal(f.api.beginSelectionGesture('drag','doc-1'),true);
    f.select(node,0,node,1);f.selectionChanged();f.tick();f.select(node,0,node,8);f.selectionChanged();f.tick();assert.equal(f.autoCalls.length,0);
    assert.equal(f.api.completeSelectionGesture('drag','doc-1'),true);f.tick();assert.equal(f.autoCalls.length,0);
    f.select(node,2,node,14);f.selectionChanged();f.tick();assert.equal(f.autoCalls.length,0,'Range changed after first frame');
    f.selectionChanged();f.tick();assert.equal(f.autoCalls.length,0,'Even same-range selectionchange requires stable frames');
    f.tick();assert.equal(f.autoCalls.length,1);assert.equal(JSON.parse(f.autoCalls[0][2]).quote,'stable final');
    f.select(node,2,node,node.length);f.selectionChanged();f.flush();assert.equal(f.autoCalls.length,1,'Later handle motion does not autosave');
    assert.equal(f.api.beginSelectionGesture('same-session','doc-1'),false);
    assert.equal(f.api.endSelectionSession('doc-1'),true);autoGesture(f,'next-session');f.flush();assert.equal(f.autoCalls.length,2);f.immutable();
});

test('range changes without a selectionchange event also require two new stable frames',()=>{
    const node=text('First second'),f=fixture([element('P',node)]);f.api.setAutoHighlightEnabled(true,'doc-1');
    f.select(node,0,node,5);autoGesture(f);f.tick();f.select(node,6,node,12);f.tick();
    assert.equal(f.autoCalls.length,0);f.tick();assert.equal(f.autoCalls.length,0);f.tick();
    assert.equal(f.autoCalls.length,1);assert.equal(JSON.parse(f.autoCalls[0][2]).quote,'second');
});

test('auto exact-range dedup uses attached highlights across sessions but retries unpersisted emissions in a fresh session',()=>{
    const node=text('repeat repeat'),f=fixture([element('P',node)]),saved=saveRange(f,node,0,6);
    f.set([saved]);f.api.setAutoHighlightEnabled(true,'doc-1');f.select(node,0,node,6);autoGesture(f,'existing');f.flush();assert.equal(f.autoCalls.length,0);
    f.api.endSelectionSession('doc-1');f.select(node,7,node,13);autoGesture(f,'different-place');f.flush();assert.equal(f.autoCalls.length,1);
    assert.equal(f.api.beginSelectionGesture('same-session','doc-1'),false,'Pending host writes cannot emit again within the session');
    f.api.endSelectionSession('doc-1');autoGesture(f,'same-place');f.flush();assert.equal(f.autoCalls.length,2,'Rejected or failed host writes stay retryable in a new session');
    f.set([saved,{id:'second',anchor:JSON.parse(f.autoCalls[1][2])}]);
    f.api.endSelectionSession('doc-1');autoGesture(f,'persisted');f.flush();assert.equal(f.autoCalls.length,2,'Persisted exact range blocks another session');
    f.api.endSelectionSession('doc-1');f.set([]);f.select(node,0,node,6);autoGesture(f,'removed-saved');f.flush();
    assert.equal(f.autoCalls.length,3,'A removed saved annotation is not permanently treated as attached');
});

test('cancelling auto preserves the once-per-session latch and stale cancellation cannot cancel live work',()=>{
    const node=text('First second'),f=fixture([element('P',node)]);f.api.setAutoHighlightEnabled(true,'doc-1');
    f.select(node,0,node,5);autoGesture(f,'first');f.tick();assert.equal(f.api.cancelAutoCapture('old-doc'),false);f.tick();assert.equal(f.autoCalls.length,1);
    assert.equal(f.api.cancelAutoCapture('doc-1'),true);assert.equal(f.api.beginSelectionGesture('again','doc-1'),false);
    assert.equal(f.api.endSelectionSession('old-doc'),false);assert.equal(f.api.beginSelectionGesture('still-latched','doc-1'),false);
    f.api.endSelectionSession('doc-1');f.select(node,6,node,12);autoGesture(f,'second');f.tick();f.api.cancelAutoCapture('doc-1');f.flush();
    assert.equal(f.autoCalls.length,1);f.selectionChanged();f.flush();assert.equal(f.autoCalls.length,1,'Selection change cannot rearm canceled gesture');
});

test('superseding native DOWN invalidates the previous UP and pending frames without creating a debounce-only save',()=>{
    const node=text('One two'),f=fixture([element('P',node)]);f.api.setAutoHighlightEnabled(true,'doc-1');f.select(node,0,node,3);
    autoGesture(f,'old');f.tick();assert.equal(f.api.beginSelectionGesture('new','doc-1'),true);f.flush();assert.equal(f.autoCalls.length,0);
    assert.equal(f.api.completeSelectionGesture('old','doc-1'),false);f.select(node,4,node,7);f.selectionChanged();f.flush();assert.equal(f.autoCalls.length,0);
    assert.equal(f.api.completeSelectionGesture('new','doc-1'),true);f.flush();assert.equal(f.autoCalls.length,1);assert.equal(f.autoCalls[0][1],'new');
});

for(const action of ['disable-auto','disable-mode','clear','cancel','end','resize','pagehide','document-change']) {
    test(`pending auto capture cannot survive ${action}`,()=>{
        const node=text('Quote for auto and resize'),f=fixture([element('P',node)]),saved=saveRange(f,node,0,5);
        f.set([saved]);f.api.setAutoHighlightEnabled(true,'doc-1');f.select(node,6,node,14);autoGesture(f,'pending');f.tick();
        if(action==='disable-auto') f.api.setAutoHighlightEnabled(false,'doc-1');
        if(action==='disable-mode') f.api.setSelectionMode(false,'doc-1',false);
        if(action==='clear') f.api.clearSelection();
        if(action==='cancel') f.api.cancelAutoCapture('doc-1');
        if(action==='end') f.api.endSelectionSession('doc-1');
        if(action==='resize') assert.equal(f.api.beginResize('highlight','resize','doc-1'),true);
        if(action==='pagehide') f.windowListeners.get('pagehide')();
        if(action==='document-change') f.context._inkreadGeneration='doc-2';
        f.flush();assert.equal(f.autoCalls.length,0);
        if(action==='resize') {
            f.select(node,6,node,14);f.selectionChanged();f.flush();assert.equal(f.autoCalls.length,0);
            assert.equal(f.api.beginSelectionGesture('resize-drag','doc-1'),false);
            assert.equal(f.api.cancelResize('highlight','resize','doc-1'),true);f.flush();assert.equal(f.autoCalls.length,0);
        }
        f.immutable();
    });
}

test('captureSelection is side-effect free for auto; the explicit native manual capture cancellation prevents a duplicate write',()=>{
    const node=text('Manual versus automatic'),f=fixture([element('P',node)]);f.api.setAutoHighlightEnabled(true,'doc-1');f.select(node,0,node,6);
    autoGesture(f,'manual');assert.equal(f.capture().quote,'Manual');f.tick();assert.equal(f.autoCalls.length,0);
    f.api.cancelAutoCapture('doc-1');const manual=f.capture();f.flush();assert.equal(f.autoCalls.length,0);assert.equal(manual.quote,'Manual');
    autoGesture(f,'automatic');assert.equal(f.capture().quote,'Manual');f.flush();assert.equal(f.autoCalls.length,1,'Read-only capture did not cancel auto');
});

test('auto waits for a noncollapsed valid selection and never emits a capture-limit error envelope',()=>{
    const node=text('Selection arrives later'),f=fixture([element('P',node)],{selectionMode:false});f.api.setAutoHighlightEnabled(true,'doc-1');
    autoGesture(f,'late');f.flush();assert.equal(f.autoCalls.length,0);
    f.select(node,0,node,9);f.selectionChanged();f.flush();assert.equal(f.autoCalls.length,0,'Capture must be enabled by native selection session');
    f.api.setSelectionMode(true,'doc-1');f.tick();assert.equal(f.autoCalls.length,0);f.tick();assert.equal(f.autoCalls.length,1);
    const large=text('oversized quote'),g=fixture([element('P',large)],{captureBudget:10});g.api.setAutoHighlightEnabled(true,'doc-1');g.select(large,0,large,large.length);
    autoGesture(g);g.flush();assert.equal(g.autoCalls.length,0);
});

test('provider ActionMode replacement sync cannot restart pending stability or reset the once-per-session latch',()=>{
    const node=text('Keep selection session'),f=fixture([element('P',node)]);f.api.setAutoHighlightEnabled(true,'doc-1');f.select(node,0,node,4);
    autoGesture(f,'original-down');f.tick();assert.equal(f.autoCalls.length,0);
    f.api.setSelectionMode(true,'doc-1',false);f.tick();assert.equal(f.autoCalls.length,1,'Repeated native enabled sync leaves original UP capture intact');
    f.api.setSelectionMode(true,'doc-1',false);f.selectionChanged();f.flush();assert.equal(f.autoCalls.length,1);
    assert.equal(f.api.beginSelectionGesture('provider-replacement','doc-1'),false);
    f.api.setSelectionMode(false,'doc-1',false);f.api.setSelectionMode(true,'doc-1',false);
    assert.equal(f.api.beginSelectionGesture('mode-sync','doc-1'),false,'Only an explicit endSelectionSession resets the latch');
});

test('a missing Android callback does not register a range as already emitted in a later explicit session',()=>{
    const node=text('Bridge recovery'),f=fixture([element('P',node)]);f.api.setAutoHighlightEnabled(true,'doc-1');f.select(node,0,node,6);
    const callback=f.context.Android.onAutoAnnotation;delete f.context.Android.onAutoAnnotation;
    autoGesture(f,'missing-bridge');f.flush();assert.equal(f.autoCalls.length,0);
    f.context.Android.onAutoAnnotation=callback;f.api.endSelectionSession('doc-1');autoGesture(f,'bridge-restored');f.flush();assert.equal(f.autoCalls.length,1);
});

test('Undo forgets the session pending emission once, without rearming its current selection session',()=>{
    const node=text('First second'),f=fixture([element('P',node)]);f.api.setAutoHighlightEnabled(true,'doc-1');
    assert.equal(f.api.forgetLastAutoCapture('doc-1'),false);
    f.select(node,0,node,5);autoGesture(f,'first');f.flush();assert.equal(f.autoCalls.length,1);
    f.api.endSelectionSession('doc-1');f.select(node,6,node,12);autoGesture(f,'second');f.flush();assert.equal(f.autoCalls.length,2);
    assert.equal(f.api.forgetLastAutoCapture('old-doc'),false);
    assert.equal(f.api.forgetLastAutoCapture('doc-1'),true);assert.equal(f.api.forgetLastAutoCapture('doc-1'),false,'Repeated Undo does not change an older session');
    f.selectionChanged();f.flush();assert.equal(f.autoCalls.length,2);assert.equal(f.api.beginSelectionGesture('still-active','doc-1'),false);
    f.api.endSelectionSession('doc-1');f.select(node,0,node,5);autoGesture(f,'first-again');f.flush();assert.equal(f.autoCalls.length,3,'Earlier unpersisted emission is retryable');
    f.api.endSelectionSession('doc-1');assert.equal(f.api.forgetLastAutoCapture('doc-1'),false,'Ending the session drops pending emission bookkeeping');
    f.select(node,6,node,12);autoGesture(f,'second-again');f.flush();assert.equal(f.autoCalls.length,4,'Undone range can be selected in a new session');
});

test('Undo does not bypass persisted highlight dedup before native removal reaches setAnnotations',()=>{
    const node=text('Persisted quote'),f=fixture([element('P',node)]);f.api.setAutoHighlightEnabled(true,'doc-1');f.select(node,0,node,node.length);
    autoGesture(f,'original');f.flush();assert.equal(f.autoCalls.length,1);
    f.set([{id:'saved-auto',anchor:JSON.parse(f.autoCalls[0][2])}]);assert.equal(f.api.forgetLastAutoCapture('doc-1'),true);
    f.api.endSelectionSession('doc-1');autoGesture(f,'before-removal');f.flush();assert.equal(f.autoCalls.length,1);
    f.set([]);f.api.endSelectionSession('doc-1');autoGesture(f,'after-removal');f.flush();assert.equal(f.autoCalls.length,2);
});

test('Copy completion revokes resize and auto capture without clearing the live native range',()=>{
    const node=text('Native Copy resized quotation'),f=fixture([element('P',node)]),saved=saveRange(f,node,7,11,'copy-resize');
    f.set([saved]);f.api.setAutoHighlightEnabled(true,'doc-1');assert.equal(f.api.beginResize('copy-resize','copy-edit','doc-1'),true);
    f.select(node,7,node,19);const selection=f.context.getSelection(),range=selection.getRangeAt(0),quote=selection.toString();
    let clears=0;const remove=selection.removeAllRanges;selection.removeAllRanges=function(){clears++;return remove.call(this);};
    assert.equal(f.api.cancelResize('copy-resize','wrong-token','doc-1',false),false);
    assert.equal(f.api.cancelResize('copy-resize','copy-edit','old-doc',false),false);
    assert.equal(f.api.captureResize('copy-resize','copy-edit','doc-1')!==null,true);
    assert.equal(f.api.cancelResize('copy-resize','copy-edit','doc-1',false),true);
    assert.equal(f.api.captureResize('copy-resize','copy-edit','doc-1'),null,'Edit request is revoked immediately');
    assert.equal(f.api.cancelResize('copy-resize','copy-edit','doc-1'),false,'Late default cancel cannot clear the pending Copy range');
    f.api.setSelectionMode(false,'doc-1',false);f.selectionChanged();f.flush();
    assert.equal(f.context.getSelection(),selection);assert.equal(selection.getRangeAt(0),range);
    assert.equal(selection.toString(),quote);assert.equal(selection.rangeCount,1);assert.equal(selection.isCollapsed,false);
    assert.equal(clears,0);assert.equal(f.autoCalls.length,0);f.immutable();
});

test('endSelectionSession fences old pending animation callbacks before a new session can emit the same range',()=>{
    const node=text('Retry this quote'),f=fixture([element('P',node)]);f.api.setAutoHighlightEnabled(true,'doc-1');f.select(node,0,node,node.length);
    autoGesture(f,'old-session');f.tick();assert.equal(f.autoCalls.length,0);
    f.api.endSelectionSession('doc-1');autoGesture(f,'new-session');f.tick();assert.equal(f.autoCalls.length,0);
    f.tick();assert.equal(f.autoCalls.length,1);assert.equal(f.autoCalls[0][1],'new-session');
    f.selectionChanged();f.flush();assert.equal(f.autoCalls.length,1);
});

test('legacy plain-tap exclusions preserve standalone labels, options, contenteditable attributes and null hit targets',()=>{
    const node=text('Existing highlighted text'),f=fixture([element('P',node)]),saved=saveRange(f,node,0,8);
    f.setRects(()=>[fixedRect()]);f.set([saved]);f.api.setSelectionMode(false,'doc-1');
    for(const tag of ['LABEL','OPTION']) {
        const control=element(tag,element('SPAN','native control'));f.setHitTarget(control);
        assert.deepEqual(target(f),{kind:'blocked'},`Standalone ${tag} stays native`);
        f.setHitTarget(control.firstChild);assert.deepEqual(target(f),{kind:'blocked'},`${tag} descendants stay native`);
    }
    for(const value of ['', 'true', 'false', 'plaintext-only']) {
        const editable=element('DIV',element('SPAN','native content'));editable.setAttribute('contenteditable',value);
        f.setHitTarget(editable.firstChild);assert.deepEqual(target(f),{kind:'blocked'},`Presence of contenteditable=${JSON.stringify(value)} blocks page tap`);
    }
    f.setHitTarget(null);assert.deepEqual(target(f),{kind:'blocked'},'No hit must not turn the page or open a highlight');
    f.setHitTarget(f.frame);assert.deepEqual(target(f),{kind:'annotation',id:'highlight'});f.immutable();
});

test('inline script element and text duplicate rectangles do not darken fallback highlights twice',()=>{
    const node=text('English 中文 text'), f=fixture([element('P',node)]);
    f.setRects(()=>[fixedRect(),fixedRect()]);
    const saved=saveRange(f,node,0,node.length);f.set([saved]);
    const overlay=f.body.childNodes.find(n=>n.id==='inkread-annotation-overlay');
    assert.ok(overlay);assert.equal(overlay.childNodes.length,1);
});
