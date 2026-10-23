// Focused runtime tests: deterministic DOM geometry, not a substitute for WebView tests.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const runtime = fs.readFileSync(path.join(__dirname, '../../main/assets/eink-formatting.js'), 'utf8');
const defaults = {fontSize:18,lineHeight:1,marginLeft:24,marginRight:24,marginTop:24,marginBottom:24,
    letterSpacing:.5,wordSpacing:0,textDarkness:1,textWeight:400,paragraphGap:1,paragraphIndent:0,
    alignment:'start',imageContrast:1,imageGrayscale:false,fontCss:''};

function fixture({layoutUnit=0, layoutScale=1, nodeCount=30, serializeStyles=false, identity=null, savedAnchor=null, mediaCount=0, metricHeight=null}={}) {
    const documentListeners = new Map();
    const listeners = new Map(), frameListeners = new Map(), fontListeners = new Map();
    const variables = {}, queue = new Map(), calls = [], timers = new Map(), bodyChildren = [];
    let serial=0, prefix=0, glyphScale=1, fontScale=1;
    const context = {console, Math, Object, Array, isFinite, innerWidth:411.42, innerHeight:760,
        _inkreadInitialFormatting:{...defaults}, _inkreadGeneration:'unchanged-document',
        _inkreadReadingIdentity:identity, _inkreadInitialReadingAnchor:savedAnchor,
        NodeFilter:{SHOW_TEXT:4,FILTER_ACCEPT:1,FILTER_REJECT:2},
        requestAnimationFrame:cb=>{queue.set(++serial,cb);return serial;},
        cancelAnimationFrame:id=>queue.delete(id),
        setTimeout:cb=>{timers.set(++serial,cb);return serial;}, clearTimeout:id=>timers.delete(id),
        addEventListener:(name,cb)=>listeners.set(name,cb),removeEventListener:name=>listeners.delete(name),
        Android:{onInitialPaginationReady:(...args)=>calls.push(['ready',...args]),onRepaginated:(...args)=>calls.push(['reflow',...args]),
            onReadingAnchor:(...args)=>calls.push(['anchor',...args]), onReadingAnchorRestore:(...args)=>calls.push(['restore',...args])}
    };
    const rawStyles = new WeakMap();
    function createStyle() {
        const values = {};
        const style = new Proxy(values,{get:(target,key)=>{
            const value=target[key];
            // CSSOM serializes declaration values with less precision than the
            // layout engine retains from the original assigned CSS number.
            return serializeStyles && /^(width|columnWidth|columnGap)$/.test(String(key)) && value ?
                Number(parseFloat(value).toPrecision(6))+'px' : value;
        }});
        rawStyles.set(style,values);return style;
    }
    const assigned = (style,key) => rawStyles.get(style)[key];
    const style = createStyle();
    const nodes=Array.from({length:nodeCount},(_,i)=>({data:`Paragraph${i}: `+'abcdefghijklmn 中文'.repeat(60),
        get length(){return this.data.length;}, parentElement:{tagName:'P'}}));
    const layoutValue = value => layoutUnit ? Math.floor(parseFloat(value)*layoutScale/layoutUnit+1e-8)*layoutUnit/layoutScale : parseFloat(value);
    const actualStride = () => layoutValue(assigned(style,'width')) + layoutValue(assigned(style,'columnGap'));
    function geometry(node, offset) {
        const width = layoutValue(assigned(style,'width')), height = layoutValue(style.height);
        const charWidth = parseFloat(variables['--reader-font-size'])*fontScale*.5*glyphScale;
        const line = parseFloat(variables['--reader-font-size'])*fontScale*Number(variables['--reader-line-height']);
        const chars = Math.max(1,Math.floor(width/charWidth)), rows = Math.max(1,Math.floor(height/line));
        let absolute = prefix + offset;
        for(let i=0;i<nodes.indexOf(node);i++) absolute+=nodes[i].length;
        const col=Math.floor(absolute/(chars*rows)), row=Math.floor(absolute/chars)%rows;
        const translation=Number((style.transform||'').match(/translateX\(-([0-9.]+)px\)/)?.[1]||0);
        const left=parseFloat(style.left)+col*actualStride()+(absolute%chars)*charWidth-translation;
        const top=parseFloat(style.top)+row*line;
        return {left,top,right:left+charWidth,bottom:top+line,width:charWidth,height:line};
    }
    const media=Array.from({length:mediaCount},(_,i)=>({
        source:'https://inkread.local/saved-library-assets/'+i,
        getAttribute(name){return name==='src'?this.source:null;},
        setAttribute(name,value){if(name==='src')this.source=value;},
        getBoundingClientRect:()=>{
        const translation=Number((style.transform||'').match(/translateX\(-([0-9.]+)px\)/)?.[1]||0);
        const left=parseFloat(style.left)+i*actualStride()-translation,top=parseFloat(style.top);
        return {left,right:left+100,top,bottom:top+100,width:100,height:100};
    }}));
    const frame={style,addEventListener:(n,cb)=>frameListeners.set(n,cb),removeEventListener:n=>frameListeners.delete(n),
        querySelectorAll:()=>media,getBoundingClientRect:()=>({width:parseFloat(style.width),height:parseFloat(style.height)}),
        get scrollWidth(){
            if(!nodes.length)return Math.round(Math.max(1,mediaCount)*actualStride()-layoutValue(assigned(style,'columnGap')));
            const last=geometry(nodes.at(-1),nodes.at(-1).length-1);
            const translation=Number((style.transform||'').match(/translateX\(-([0-9.]+)px\)/)?.[1]||0);
            const pages=Math.floor((last.left+translation-parseFloat(style.left))/actualStride())+1;
            return Math.round(pages*actualStride()-layoutValue(assigned(style,'columnGap')));
        }};
    const fontFaceStyle={textContent:''};
    function createElement() {
        const element={style:createStyle(),children:[],attributes:{},parentElement:null,
            setAttribute:(name,value)=>element.attributes[name]=value,
            appendChild:child=>{child.parentElement=element;element.children.push(child);},
            remove:()=>{const index=bodyChildren.indexOf(element);if(index>=0)bodyChildren.splice(index,1);},
            getBoundingClientRect:()=>({left:element.parentElement.children.indexOf(element)*(
                layoutValue(assigned(element.parentElement.style,'width'))+layoutValue(assigned(element.parentElement.style,'columnGap')))})};
        return element;
    }
    const document={readyState:'complete',visibilityState:'visible',addEventListener:(name,cb)=>documentListeners.set(name,cb),removeEventListener:name=>documentListeners.delete(name),createElement,body:{appendChild:child=>bodyChildren.push(child)}, documentElement:{style:{setProperty:(name,value)=>variables[name]=String(value)},
        getBoundingClientRect:()=>({width:context.innerWidth}),clientHeight:context.innerHeight},
        fonts:{ready:Promise.resolve(), addEventListener:(n,cb)=>fontListeners.set(n,cb),removeEventListener:n=>fontListeners.delete(n)},
        getElementById:id=>id==='eink-content'?frame:fontFaceStyle,
        createTreeWalker:()=>{let i=0;return {nextNode:()=>nodes[i++]||null};},
        createRange:()=>{let node,start,end;return {setStart:(n,o)=>{node=n;start=o;},setEnd:(_,o)=>end=o,
            getBoundingClientRect:()=>geometry(node,start),
            getClientRects:()=>{
                const result=[];let previous;
                for(let i=start;i<end;i++) {const rect=geometry(node,i);if(!previous||rect.top!==previous.top||rect.left<previous.left)result.push(rect);previous=rect;}
                if(previous)result.push(previous);return result;
            }};}
    };
    Object.assign(context,{document,window:context,getComputedStyle:()=>({
        fontSize:(parseFloat(variables['--reader-font-size'])*fontScale)+'px',
        lineHeight:(parseFloat(variables['--reader-font-size'])*fontScale*Number(variables['--reader-line-height']))+'px'
    })});
    vm.createContext(context);
    let source = runtime;
    if (metricHeight !== null) {
        context.InkReadScriptFonts = {install:()=>({readingNodes:nodes,range(node,start,end){
            const range=document.createRange();range.setStart(node,start);range.setEnd(node,end);return range;
        }})};
        source = source.replace('/* Live paginated layout.',
            'window.InkReadLineMetrics = {create: function(){return {update: function(){return ' + metricHeight + ';}, invalidate: function(){}, deferred: function(){return false;}, destroy: function(){}};}}; /* Live paginated layout.');
    }
    vm.runInContext(source,context);
    async function flush() {await Promise.resolve();while(queue.size){const work=[...queue.values()];queue.clear();work.forEach(cb=>cb());await Promise.resolve();}}
    function fireResize(width,height){context.innerWidth=width;context.innerHeight=height;listeners.get('resize')();for(const cb of timers.values())cb();timers.clear();}
    return {context,frame,nodes,media,calls,documentListeners,listeners,frameListeners,fontListeners,variables,bodyChildren,actualStride,flush,fireResize,
        setPrefix:n=>prefix=n,setGlyphScale:n=>glyphScale=n,setFontScale:n=>fontScale=n,
        anchor:()=>JSON.parse(JSON.stringify(context.InkReadFormatting.currentAnchor())),
        anchorRect:()=>{const a=context.InkReadFormatting.currentAnchor();return a.element === undefined ? geometry(nodes[a.node],a.offset) : media[a.element].getBoundingClientRect();}};
}

function assertAnchorVisible(f) {
    const r=f.anchorRect(),m=f.context.InkReadFormatting.effectiveMargins();
    assert.ok(r.left>=m.left-.6 && r.right<=f.context.innerWidth-m.right+.6,JSON.stringify({r,m}));
    assert.ok(r.top>=m.top-.6 && r.bottom<=f.context.innerHeight-m.bottom+.6,JSON.stringify({r,m}));
}

test('live font/spacing/margins keep exact DOM text offset, not page fraction',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(12);const anchor=f.anchor(),oldPage=f.context._currentPage;
    f.context.InkReadFormatting.apply({...defaults,fontSize:32,lineHeight:1.8,marginLeft:49,marginRight:12,marginTop:33,marginBottom:69});
    await f.flush();assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    assert.notEqual(f.context._currentPage,oldPage);assert.equal(f.context.InkReadFormatting.documentId,'unchanged-document');
    assert.equal(f.context._pageStride,411.42);
    assert.equal(parseFloat(f.frame.style.columnGap),61);
    assert.equal(parseFloat(f.frame.style.height),760-33-69);
});

test('panel session blocks page turns and Cancel restores original style/anchor across viewport changes',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(8);const anchor=f.anchor();
    f.context.InkReadFormatting.beginSession();f.context.goToPage(15);assert.equal(f.context._currentPage,8);
    f.fireResize(411.42,390);await f.flush();assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    f.context.InkReadFormatting.apply({...defaults,fontSize:28,lineHeight:1.6});await f.flush();
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    f.context.InkReadFormatting.endSession(true,defaults);f.fireResize(411.42,760);await f.flush();
    assert.deepEqual(f.anchor(),anchor);assert.equal(f.context._currentPage,8);assertAnchorVisible(f);
    f.context.goToPage(9);assert.notDeepEqual(f.anchor(),anchor);
});

test('late ordinary images and font readiness reflow using the pre-event anchor',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(12);const anchor=f.anchor();
    f.setPrefix(9000);f.frameListeners.get('load')();await f.flush();
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);assert.ok(f.context._currentPage>12);
    f.setGlyphScale(1.3);f.fontListeners.get('loadingdone')();await f.flush();
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
});

for (const event of ['font', 'image', 'resize']) {
    test(`queued ${event} reflow retains newer rapid forward and backward turns`,async()=>{
        const f=fixture();await f.flush();f.context.goToPage(6);
        if(event==='font')f.fontListeners.get('loadingdone')();
        else if(event==='image')f.frameListeners.get('load')();
        else f.fireResize(f.context.innerWidth,f.context.innerHeight);
        // All these turns arrive after the event but before its animation frame.
        for(const page of [7,8,7,8,9])f.context.goToPage(page);
        const accepted=f.anchor();
        assert.equal(f.context._currentPage,9);
        await f.flush();
        assert.equal(f.context._currentPage,9,'A queued reflow must not roll an accepted turn back');
        assert.deepEqual(f.anchor(),accepted);assertAnchorVisible(f);
        assert.equal(f.calls.filter(c=>c[0]==='reflow').at(-1)[3],9,'Native receives the latest page, not the event-time page');
    });
}

test('initial formatting font-ready reflow cannot undo the first accepted turn',async()=>{
    const f=fixture();await f.flush();
    f.context.InkReadFormatting.apply({...defaults});
    await Promise.resolve(); // fonts.ready has queued its rAF, but it has not run.
    f.context.goToPage(1);const accepted=f.anchor();
    await f.flush();
    assert.equal(f.context._currentPage,1);
    assert.deepEqual(f.anchor(),accepted);assertAnchorVisible(f);
});

test('correlated ready, reflow and rapid command acknowledgements carry the applied ID',async()=>{
    const f=fixture();await f.flush();
    assert.deepEqual(f.calls.find(c=>c[0]==='ready'),['ready','unchanged-document',f.context._totalPages,0,0]);
    const initialPages=f.context._totalPages;
    f.context.InkReadFormatting.apply({...defaults,fontSize:22});
    assert.notEqual(f.context._totalPages,initialPages,'Exercise acknowledgements carrying a changed page count');
    const oldReport=f.calls.filter(c=>c[0]==='reflow').at(-1).slice();
    assert.equal(oldReport[4],0);
    await Promise.resolve(); // Queue font-ready reflow before the newer commands.
    for(const [page,id] of [[1,1],[2,2],[1,3]]) {
        f.context.goToPage(page,id,'unchanged-document');
        assert.deepEqual(f.calls.at(-1),['reflow','unchanged-document',f.context._totalPages,page,id]);
    }
    const accepted=f.anchor();await f.flush();
    assert.deepEqual(f.anchor(),accepted);assertAnchorVisible(f);
    assert.deepEqual(f.calls.at(-1),['reflow','unchanged-document',f.context._totalPages,1,3]);
    assert.equal(oldReport[3],0);assert.equal(oldReport[4],0,'An older posted report remains distinguishable');
});

test('correlated same-page and clamped commands acknowledge without replacing the canonical anchor',async()=>{
    const expected={node:17,offset:333};
    const f=fixture({identity:readingIdentity,savedAnchor:envelope(expected)});await f.flush();
    const restoredPage=f.context._currentPage;
    assert.deepEqual(f.calls.find(c=>c[0]==='ready'),['ready','unchanged-document',f.context._totalPages,restoredPage,0]);
    f.context.goToPage(restoredPage,1,'unchanged-document');
    assert.deepEqual(f.anchor(),expected);assert.equal(f.calls.filter(c=>c[0]==='anchor').length,0);
    assert.deepEqual(f.calls.at(-1),['reflow','unchanged-document',f.context._totalPages,restoredPage,1]);
    f.context.goToPage(f.context._totalPages+10,2,'unchanged-document');const lastAnchor=f.anchor();
    f.context.goToPage(f.context._totalPages+20,3,'unchanged-document');
    assert.deepEqual(f.anchor(),lastAnchor);
    assert.deepEqual(f.calls.at(-1),['reflow','unchanged-document',f.context._totalPages,f.context._totalPages-1,3]);
    f.context.goToPage(-10,4,'unchanged-document');
    assert.deepEqual(f.calls.at(-1),['reflow','unchanged-document',f.context._totalPages,0,4]);
    assert.deepEqual(f.calls.filter(c=>c[0]==='reflow').map(c=>c[4]),[1,2,3,4]);
});

test('correlated commands reject an old document generation or an older command ID',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(4,2,'unchanged-document');
    const accepted=f.anchor(),count=f.calls.length;
    f.context.goToPage(8,3,'old-document');
    f.context.goToPage(10,1,'unchanged-document');
    assert.equal(f.context._currentPage,4);assert.deepEqual(f.anchor(),accepted);
    assert.equal(f.calls.length,count,'Rejected commands do not acknowledge or publish an anchor');
    f.fontListeners.get('loadingdone')();await f.flush();
    assert.deepEqual(f.calls.at(-1),['reflow','unchanged-document',f.context._totalPages,4,2]);
});

test('correlated duplicate command acknowledges current geometry without replaying its old target',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(10,1,'unchanged-document');
    const accepted=f.anchor();
    f.context.InkReadFormatting.apply({...defaults,fontSize:30});await f.flush();
    const reflowedPage=f.context._currentPage;
    assert.notEqual(reflowedPage,10,'Exercise a real reflow after the command was handled');
    assert.deepEqual(f.anchor(),accepted);assertAnchorVisible(f);
    const count=f.calls.length;f.context.goToPage(10,1,'unchanged-document');
    assert.equal(f.context._currentPage,reflowedPage,'A duplicate ID must not replay the old absolute target');
    assert.deepEqual(f.anchor(),accepted);assert.equal(f.calls.length,count+1);
    assert.deepEqual(f.calls.at(-1),['reflow','unchanged-document',f.context._totalPages,reflowedPage,1]);
});

test('correlated formatting sessions block commands without advancing their applied ID',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(4,1,'unchanged-document');
    const accepted=f.anchor(),count=f.calls.length;
    f.context.InkReadFormatting.beginSession();f.context.goToPage(5,2,'unchanged-document');
    assert.equal(f.calls.length,count);assert.deepEqual(f.anchor(),accepted);
    f.context.InkReadFormatting.apply({...defaults,fontSize:28});await f.flush();
    assert.equal(f.calls.at(-1)[4],1);assert.deepEqual(f.anchor(),accepted);
    f.context.InkReadFormatting.endSession(true,defaults);await f.flush();
    assert.equal(f.calls.at(-1)[4],1);assert.deepEqual(f.anchor(),accepted);
    f.context.goToPage(5,3,'unchanged-document');
    assert.deepEqual(f.calls.at(-1),['reflow','unchanged-document',f.context._totalPages,5,3]);
});

test('correlated legacy one-argument navigation keeps its behavior and the last native command ID',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(2,7,'unchanged-document');
    const count=f.calls.length;f.context.goToPage(3);
    assert.equal(f.context._currentPage,3);assert.equal(f.calls.length,count,'Legacy calls do not force a native acknowledgement');
    f.frameListeners.get('load')();await f.flush();
    assert.deepEqual(f.calls.at(-1),['reflow','unchanged-document',f.context._totalPages,3,7]);
});

test('extreme insets are temporary effective values and retain one complete requested line',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(12);const anchor=f.anchor();f.fireResize(160,210);await f.flush();
    const requested={...defaults,fontSize:48,lineHeight:2.5,marginLeft:96,marginRight:96,marginTop:96,marginBottom:96};
    f.context.InkReadFormatting.apply(requested);await f.flush();
    assert.ok(parseFloat(f.frame.style.width)>=72);assert.ok(parseFloat(f.frame.style.height)>=128);
    assert.ok(f.context._totalPages>500);assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    assert.equal(requested.marginTop,96);assert.equal(requested.marginLeft,96);
    f.fireResize(800,1000);await f.flush();
    assert.deepEqual(JSON.parse(JSON.stringify(f.context.InkReadFormatting.effectiveMargins())),{left:96,right:96,top:96,bottom:96});
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
});

test('minimum preview dimensions use Android-scaled computed font metrics',async()=>{
    const f=fixture();await f.flush();f.context.goToPage(12);const anchor=f.anchor();
    f.setFontScale(1.6);f.fireResize(300,300);await f.flush();
    const requested={...defaults,fontSize:48,lineHeight:2.5,marginLeft:96,marginRight:96,marginTop:96,marginBottom:96};
    f.context.InkReadFormatting.apply(requested);await f.flush();
    assert.ok(parseFloat(f.frame.style.height)>=48*1.6*2.5+8-1e-6);
    assert.ok(parseFloat(f.frame.style.width)>=48*1.6*1.5-1e-6);
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    assert.equal(requested.marginTop,96);assert.equal(requested.marginLeft,96);
    f.fireResize(1000,1200);await f.flush();
    assert.deepEqual(JSON.parse(JSON.stringify(f.context.InkReadFormatting.effectiveMargins())),{left:96,right:96,top:96,bottom:96});
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
});

test('image controls are variables only, pagehide removes listeners and queued reflow',async()=>{
    const f=fixture();await f.flush();f.context.InkReadFormatting.apply({...defaults,imageContrast:1.7,imageGrayscale:true,textWeight:700});await f.flush();
    assert.equal(f.variables['--reader-image-contrast'],'1.7');assert.equal(f.variables['--reader-image-grayscale'],'1');
    assert.equal(f.variables['--reader-text-weight'],'700');
    f.frameListeners.get('load')();const count=f.calls.length;f.listeners.get('pagehide')();await f.flush();
    assert.equal(f.calls.length,count);assert.equal(f.frameListeners.size,0);assert.equal(f.fontListeners.size,0);assert.ok(!f.listeners.has('resize'));
    assert.equal(f.bodyChildren.length,0,'The hidden column measurement is removed on pagehide');
});

for (const {name,layoutScale,width,height} of [
    {name:'phone',layoutScale:1,width:220,height:276},
    {name:'fractional phone',layoutScale:2.625,width:596/2.625,height:252}
]) {
    test(`${name}: measured column pitch prevents accumulated drift at maximum scaled formatting`,async()=>{
        const f=fixture({layoutUnit:1/64,layoutScale,serializeStyles:true});await f.flush();
        f.context.goToPage(11);const anchor=f.anchor(),node=f.nodes[anchor.node],text=node.data;
        f.context.InkReadFormatting.beginSession();
        f.setFontScale(1.6);f.fireResize(width,height);await f.flush();
        const requested={...defaults,fontSize:48,lineHeight:2.5,marginLeft:96,marginRight:96,marginTop:96,marginBottom:96};
        f.context.InkReadFormatting.apply(requested);await f.flush();
        assert.ok(f.context._currentPage>500,'Exercise hundreds of independently rounded column advances');
        assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
        assert.equal(f.context._pageStride,f.actualStride());
        assert.notEqual(f.context._pageStride,width,'The actual column advance differs from the fractional viewport');
        assert.equal(f.context._vw,width,'Do not round or change the actual viewport');
        assert.ok(parseFloat(f.frame.style.height)>=200-1e-6);
        assert.ok(parseFloat(f.frame.style.width)>=115.2-1e-6);
        assert.equal(f.bodyChildren.length,1,'Reuse one hidden measurement outside the article');
        assert.equal(f.bodyChildren[0].attributes['aria-hidden'],'true');
        assert.equal(f.bodyChildren[0].attributes.inert,'');
        assert.equal(f.nodes[anchor.node],node);assert.equal(node.data,text);
        f.setPrefix(9000);f.frameListeners.get('load')();await f.flush();
        assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
        f.setGlyphScale(1.3);f.fontListeners.get('loadingdone')();await f.flush();
        assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
        f.fireResize(width+37.25,height+19);await f.flush();
        assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
        f.context.InkReadFormatting.endSession(true,defaults);f.fireResize(411.42,760);await f.flush();
        assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
        assert.equal(f.bodyChildren.length,1);
    });
}

for (const nodeCount of [0,1]) test(`column measurement handles ${nodeCount ? 'one-glyph' : 'text-free'} single-page documents`,async()=>{
    const f=fixture({layoutUnit:1/64,nodeCount});
    if(nodeCount)f.nodes[0].data='A';
    await f.flush();
    f.fireResize(220,276);await f.flush();
    f.setFontScale(1.6);
    f.context.InkReadFormatting.apply({...defaults,fontSize:48,lineHeight:2.5,marginLeft:96,marginRight:96});await f.flush();
    assert.equal(f.context._totalPages,1);
    assert.equal(f.context._pageStride,f.actualStride());
    assert.equal(f.nodes.length,nodeCount,'No synthetic source text or anchor is introduced');
    if(nodeCount)assert.equal(f.nodes[0].data,'A');
    assert.equal(f.bodyChildren[0].children.length,2);
    assert.match(f.bodyChildren[0].style.cssText,/visibility:hidden/);
    assert.match(f.bodyChildren[0].style.cssText,/pointer-events:none/);
    assert.match(f.bodyChildren[0].style.cssText,/overflow:hidden/);
    assert.equal(f.bodyChildren[0].children[1].style.breakBefore,'column');
});

for (const width of [412.19049072265625,342.0952453613281]) {
    test(`CSSOM precision: probe retains original column dimensions at ${width}px`,async()=>{
        const f=fixture({layoutUnit:1/64,layoutScale:2.625,serializeStyles:true});await f.flush();
        f.fireResize(width,760);await f.flush();
        assert.notEqual(parseFloat(f.frame.style.width),width-48,'Exercise shortened CSS declaration serialization');
        f.context.goToPage(12);
        assert.ok(Math.abs(f.anchorRect().left-24)<.001,'Page twelve must not accumulate a layout-unit error per page');
        assert.equal(f.context._pageStride,f.actualStride(),'Probe must measure the same unrounded inputs as the article');
        const anchor=f.anchor();f.context.InkReadFormatting.beginSession();
        f.context.InkReadFormatting.apply({...defaults,fontSize:48,lineHeight:2.5});await f.flush();
        assert.equal(f.context._pageStride,f.actualStride());
        assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    });
}

const readingIdentity={itemId:'saved-A',contentRevision:'content-v1',documentFingerprint:'body-and-metadata-v1'};
function envelope(anchor, overrides={}) {
    return JSON.stringify({version:1,...readingIdentity,anchor,...overrides});
}

test('reopen restores the exact logical anchor before ready and never emits initial page zero',async()=>{
    const first=fixture({identity:readingIdentity});await first.flush();first.context.goToPage(12);
    const saved=first.context.InkReadFormatting.exportReadingAnchor(), expected=first.anchor();
    const second=fixture({identity:readingIdentity,savedAnchor:saved});await second.flush();
    assert.deepEqual(second.anchor(),expected);assertAnchorVisible(second);
    assert.equal(second.context._currentPage,12);
    assert.equal(second.calls.find(c=>c[0]==='ready')[3],12,'Native controller receives restored page');
    assert.equal(second.calls.find(c=>c[0]==='restore')[2],'restored');
    assert.equal(second.calls.filter(c=>c[0]==='anchor').length,0,'Initialization never writes page zero');
    assert.equal(second.context.InkReadFormatting.exportReadingAnchor(),saved);
});

test('saved anchor persists across fresh DOM, new viewport, formatting and late font/image reflow',async()=>{
    const anchor={node:17,offset:333}, saved=envelope(anchor);
    const f=fixture({identity:readingIdentity,savedAnchor:saved});await f.flush();
    f.fireResize(300,500);await f.flush();
    f.context.InkReadFormatting.beginSession();
    f.context.InkReadFormatting.apply({...defaults,fontSize:32,lineHeight:1.8});await f.flush();
    f.setPrefix(3000);f.frameListeners.get('load')();await f.flush();
    f.setGlyphScale(1.2);f.fontListeners.get('loadingdone')();await f.flush();
    f.context.InkReadFormatting.endSession(false,{...defaults,fontSize:32,lineHeight:1.8});await f.flush();
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    for(const call of f.calls.filter(c=>c[0]==='anchor')) assert.deepEqual(JSON.parse(call[4]).anchor,anchor);
});

test('accepted turns publish immediately; blocked formatting turns do not overwrite the anchor',async()=>{
    const f=fixture({identity:readingIdentity});await f.flush();f.context.goToPage(3);
    const calls=f.calls.filter(c=>c[0]==='anchor');assert.equal(calls.length,1);
    assert.equal(calls[0][1],'unchanged-document');assert.equal(calls[0][2],readingIdentity.itemId);
    assert.equal(calls[0][3],readingIdentity.contentRevision);assert.deepEqual(JSON.parse(calls[0][4]).anchor,f.anchor());
    f.context.InkReadFormatting.beginSession();f.context.goToPage(8);
    assert.equal(f.calls.filter(c=>c[0]==='anchor').length,1);
    assert.equal(f.context._currentPage,3);
});

test('document revision, metadata fingerprint and item identity changes explicitly fall back to the beginning',async()=>{
    for(const override of [{contentRevision:'v2'},{documentFingerprint:'new-title'},{itemId:'saved-B'}]) {
        const f=fixture({identity:readingIdentity,savedAnchor:envelope({node:17,offset:333},override)});await f.flush();
        assert.equal(f.context._currentPage,0);assert.equal(f.calls.find(c=>c[0]==='restore')[2],'changed');
        assert.equal(f.calls.filter(c=>c[0]==='anchor').length,0);
    }
});

test('invalid and out-of-range anchors are rejected without clamping to an unrelated glyph',async()=>{
    for(const savedAnchor of ['{broken',envelope({node:9999,offset:3}),envelope({node:1,offset:99999}),
        envelope({node:1,offset:-1}),envelope({node:1.5,offset:0}),envelope({element:0,offset:0})]) {
        const f=fixture({identity:readingIdentity,savedAnchor});await f.flush();
        assert.equal(f.context._currentPage,0);assert.equal(f.calls.find(c=>c[0]==='restore')[2],'invalid');
    }
});

test('UTF16 surrogate halves are not accepted as resume positions',async()=>{
    const f=fixture({identity:readingIdentity,savedAnchor:envelope({node:1,offset:1})});
    f.nodes[1].data='😀 reading text';await f.flush();
    assert.equal(f.calls.find(c=>c[0]==='restore')[2],'invalid');
});

test('image-only saved document restores a media anchor, not a fabricated text node',async()=>{
    const f=fixture({identity:readingIdentity,nodeCount:0,mediaCount:4,savedAnchor:envelope({element:2,offset:0})});
    await f.flush();assert.equal(f.context._currentPage,2);assert.deepEqual(f.anchor(),{element:2,offset:0});
    assertAnchorVisible(f);assert.equal(JSON.parse(f.context.InkReadFormatting.exportReadingAnchor()).anchor.element,2);
});

test('background and exit publish the canonical anchor once each; destroyed documents stop publishing',async()=>{
    const f=fixture({identity:readingIdentity,savedAnchor:envelope({node:17,offset:333})});await f.flush();
    f.context.document.visibilityState='hidden';f.documentListeners.get('visibilitychange')();
    assert.equal(f.calls.filter(c=>c[0]==='anchor').length,1);
    f.listeners.get('pagehide')();assert.equal(f.calls.filter(c=>c[0]==='anchor').length,2);
    assert.equal(f.context.InkReadFormatting.publishReadingAnchor(),null);
    assert.equal(f.calls.filter(c=>c[0]==='anchor').length,2);
});


test('new offline image mappings refresh existing media elements without a document reload or anchor reset',async()=>{
    const f=fixture({identity:readingIdentity,nodeCount:0,mediaCount:4,savedAnchor:envelope({element:2,offset:0})});
    await f.flush();const element=f.media[2], anchor=f.anchor();
    f.context.InkReadFormatting.refreshLocalImages('new-resource-version');
    assert.equal(f.media[2],element);
    assert.equal(element.source,'https://inkread.local/saved-library-assets/2?revision=new-resource-version');
    f.frameListeners.get('load')();await f.flush();
    assert.deepEqual(f.anchor(),anchor);assertAnchorVisible(f);
    assert.equal(f.context.InkReadFormatting.documentId,'unchanged-document');
});

test('independent script sizes update CSS without changing logical anchors or the other size', async()=>{
    const f=fixture(); await f.flush(); f.context.goToPage(4);
    const before=f.anchor(), nodes=[...f.nodes]; f.context.InkReadFormatting.beginSession();
    f.context.InkReadFormatting.apply({...defaults,fontSize:18,chineseFontSize:30}); await f.flush();
    assert.equal(f.variables['--reader-font-size'],'18px');
    assert.equal(f.variables['--reader-cjk-scale'],(30/18)+'em');
    assert.deepEqual(f.anchor(),before); assertAnchorVisible(f);
    f.context.InkReadFormatting.apply({...defaults,fontSize:24,chineseFontSize:30}); await f.flush();
    assert.equal(f.variables['--reader-font-size'],'24px');
    assert.equal(f.variables['--reader-cjk-scale'],'1.25em');
    assert.deepEqual(f.anchor(),before); assert.deepEqual(f.nodes,nodes);
    f.context.InkReadFormatting.endSession(true,defaults); await f.flush();
    assert.equal(f.variables['--reader-cjk-scale'],'1em');
    assert.deepEqual(f.anchor(),before); assertAnchorVisible(f);
});

test('largest script reserves a complete line in a short viewport', async()=>{
    const f=fixture(); await f.flush(); f.fireResize(200,120); await f.flush();
    f.context.InkReadFormatting.apply({...defaults,fontSize:12,chineseFontSize:48,lineHeight:2.5,
        marginLeft:96,marginRight:96,marginTop:96,marginBottom:96}); await f.flush();
    const margins=f.context.InkReadFormatting.effectiveMargins();
    assert.ok(200-margins.left-margins.right >= 48*1.5-0.01);
    assert.ok(120-margins.top-margins.bottom >= 120-0.01);
});

// Focused metric-host tests use explicit fake font/line boxes. Native WebView
// tests remain the oracle for real fallback metrics and handle hit-testing.
function metricsFixture({zoom=1, natural=1.25, requested=1, english=18, chinese=24, tag='P', nested=true, excluded=false, boundary=false, codeInside=false, language=null, rootBlock=false, boundaryLine=null, important={}, uniformExtra=0, typed=true, boundaryChild=false, competingRules=false}={}) {
    let measures=0, selected=false, failMeasure=false, probeLanguages=[];
    const document={readyState:'loading',addEventListener(){},removeEventListener(){}};
    const camel=s=>s.replace(/-([a-z])/g,(_,c)=>c.toUpperCase());
    function style() {
        const priority={}, result={getPropertyPriority:name=>priority[name]||'', getPropertyValue:name=>result[camel(name)]||'',
            setProperty(name,value,flag=''){result[camel(name)]=value;priority[name]=flag;}};
        Object.defineProperty(result,'cssText',{set(value){
            for(const pair of value.split(';')) {const colon=pair.indexOf(':');if(colon>0){const raw=pair.slice(colon+1).trim();result.setProperty(pair.slice(0,colon).trim(),raw.replace(/!important$/,''),raw.endsWith('!important')?'important':'');}}
        }});return result;
    }
    class Element {
        constructor(tag,type=1,data='') {this.tagName=tag;this.nodeType=type;this.data=data;this.children=[];this.parentElement=null;
            this.style=style();this.attributes={};this.className='';this.ownerDocument=document;
            this.classList={contains:name=>this.className.split(' ').includes(name)};
            if(typed)this.computedStyleMap=()=>({get:()=>{const line=lineSpec(this);return line.type==='normal'?{value:'normal'}:{unit:line.type==='ratio'?'number':'px',value:line.value};}});}
        get firstChild(){return this.children[0]||null;}
        get nextSibling(){const siblings=this.parentElement?.children||[];return siblings[siblings.indexOf(this)+1]||null;}
        appendChild(child){child.parentElement=this;this.children.push(child);return child;}
        setAttribute(k,v){this.attributes[k]=v;}
        getAttribute(k){return this.attributes[k]??null;}
        get textContent(){return this.nodeType===3?this.data:this.children.map(n=>n.textContent).join('');}
        set textContent(v){this.children=[];if(v)this.appendChild(new Element(null,3,v));}
        remove(){const siblings=this.parentElement?.children; if(siblings)siblings.splice(siblings.indexOf(this),1);this.parentElement=null;}
        getBoundingClientRect(){measures++;if(failMeasure)throw Error('test metric unavailable');probeLanguages.push(this.getAttribute('lang'));const line=lineSpec(this), height=line.type==='length'?Math.max(naturalHeight(this),line.value+uniformExtra):naturalHeight(this);return {top:0,bottom:height,height,left:0,right:100,width:100};}
    }
    const el=(tag,...kids)=>{const node=new Element(tag);kids.forEach(k=>node.appendChild(typeof k==='string'?new Element(null,3,k):k));return node;};
    const head=el('HEAD'),body=el('BODY'),root=el('MAIN');body.appendChild(root);
    root.authored={fontSize:english,fontFamily:'customFallback',lineRatio:requested};if(language)root.setAttribute('lang',language);
    const cjk=el('SPAN','中文');cjk.className='eink-script-cjk';cjk.authored={fontSize:chinese};
    const link=el('A','linked ',cjk); if(nested)link.authored={fontWeight:'700'};
    const block=rootBlock?root:el(tag);block.appendChild(new Element(null,3,'English '));block.appendChild(nested?link:cjk);if(!rootBlock)root.appendChild(block);
    if(excluded)block.className='eink-metadata';
    const heading=el('H2','English heading'),headingRun=el('SPAN','nested heading');headingRun.authored={fontSize:english*3};if(boundaryChild)heading.appendChild(headingRun);heading.authored={fontSize:english*1.5};if(boundaryLine)heading.style.lineHeight=boundaryLine;if(boundary)block.appendChild(heading);
    const codeSpan=el('SPAN','代码');codeSpan.authored={fontSize:100};if(codeInside)block.appendChild(el('CODE',codeSpan));
    const pre=el('PRE',el('CODE','代码'));root.appendChild(pre);
    if(competingRules)block.style.setProperty('font-size',english+'px','important');
    for(const [name,value] of Object.entries(important)) ({block,link,cjk,heading,codeSpan}[name]).style.setProperty('line-height',value,'important');
    const records=[{parentElement:nested?link:block,fragments:[{node:cjk.firstChild}]}];
    function authored(node,key) {for(let n=node;n;n=n.parentElement)if(n.authored?.[key]!==undefined)return n.authored[key];}
    function size(node) {
        if(node.nodeType===3)return size(node.parentElement);
        if(competingRules&&node.tagName==='P'&&node.style.getPropertyPriority('font-size')!=='important')return 10*zoom;
        if(node.authored)return (node.authored.fontSize??authored(node.parentElement,'fontSize')??english)*zoom;
        const raw=node.style.fontSize;
        if(raw?.endsWith('em'))return parseFloat(raw)*size(node.parentElement);
        if(raw?.endsWith('px'))return parseFloat(raw)*zoom;
        if(node.parentElement)return size(node.parentElement);
        return 16*zoom;
    }
    function sheetValue(node,property) {
        let value='';
        for(const sheet of head.children) for(const rule of sheet.textContent.matchAll(/([^{}]+)\{([^{}]+)\}/g)) {
            const attr=rule[1].match(/\[([^=\]]+)(?:="([^"]*)")?\]/);
            if(!attr || node.getAttribute(attr[1])===null || (attr[2]!==undefined&&node.getAttribute(attr[1])!==attr[2]))continue;
            if(rule[1].includes('#eink-content ')&&node===root)continue;
            for(const declaration of rule[2].split(';')) {
                const colon=declaration.indexOf(':');if(declaration.slice(0,colon)===property)value=declaration.slice(colon+1).replace(/!important$/,'');
            }
        }
        return value;
    }
    function lineSpec(node) {
        if(!node)return {type:'ratio',value:requested};
        let raw=node.style.lineHeight||'', rule=sheetValue(node,'line-height');
        if(competingRules&&node.tagName==='P'&&node.style.getPropertyPriority('line-height')!=='important')raw='1px';
        if(rule&&node.style.getPropertyPriority('line-height')!=='important')raw=rule;
        const variable=raw.match(/^var\((--[\w-]+),\s*(.+)\)$/);
        if(variable)raw=sheetValue(node,variable[1])||variable[2];
        if(!raw)return node.authored?.lineRatio!==undefined?{type:'ratio',value:node.authored.lineRatio}:lineSpec(node.parentElement);
        if(/^(inherit|unset|revert|revert-layer)$/.test(raw))return lineSpec(node.parentElement);
        if(raw==='normal')return {type:'normal'};
        if(raw.endsWith('em'))return {type:'length',value:parseFloat(raw)*size(node)};
        if(raw.endsWith('px'))return {type:'length',value:parseFloat(raw)*zoom};
        if(raw.endsWith('%'))return {type:'length',value:parseFloat(raw)*size(node)/100};
        return {type:'ratio',value:Number(raw)};
    }
    function computed(node) {
        const fontSize=size(node), line=lineSpec(node);
        return {fontSize:fontSize+'px',lineHeight:line.type==='normal'?'normal':(line.type==='length'?line.value:fontSize*line.value)+'px',
            fontFamily:node.style.fontFamily||authored(node,'fontFamily')||'customFallback',
            fontWeight:node.style.fontWeight||authored(node,'fontWeight')||'400',fontStyle:'normal',
            display:node.style.display||(node.tagName==='SPAN'?'contents':/^(A|CODE|B|STRONG|EM|I)$/.test(node.tagName)?'inline':'block'),
            letterSpacing:'.5px',wordSpacing:'0px',direction:'ltr'};
    }
    function naturalHeight(node) {
        if(node.nodeType===3)return /\S/.test(node.data)?size(node)*natural:0;
        return Math.max(size(node)*natural,...node.children.map(n=>naturalHeight(n)));
    }
    Object.assign(document,{head,body,createElement:tag=>el(tag),createTextNode:t=>new Element(null,3,t),
        createRange:()=>{let node;return {selectNodeContents:n=>node=n,getBoundingClientRect:()=>node.getBoundingClientRect()};}});
    const context={document,innerWidth:400,getComputedStyle:computed,getSelection:()=>({isCollapsed:!selected}),
        addEventListener(){},removeEventListener(){},setTimeout(){},clearTimeout(){},NodeFilter:{},console:{warn(){}}};
    context.window=context;vm.createContext(context);vm.runInContext(runtime,context);
    const metrics=context.InkReadLineMetrics.create(root,{nodes:records});
    const settings={...defaults,fontSize:english,chineseFontSize:chinese,lineHeight:requested};
    return {metrics,settings,root,block,cjk,link,head,body,computed,heading,headingRun,codeSpan,context,probeLanguages,measures:()=>measures,setSelected:v=>selected=v,setFailure:v=>failMeasure=v,
        css:()=>head.children[0].textContent,html:()=>JSON.stringify(serialize(root))};
    function serialize(n){return {tag:n.tagName,data:n.data,attrs:n.attributes,lineHeight:n.style.lineHeight,priority:n.style.getPropertyPriority('line-height'),children:n.children.map(serialize)};}
}

for(const tag of ['P','H2','LI','TD']) {
    test(`metric floor measures nested linked script runs in ${tag} without rewriting article`,()=>{
        const f=metricsFixture({tag});const html=f.html();const leading=f.metrics.update(f.settings);
        assert.equal(leading,31,'24px fallback font *1.25, rounded outward by1px');
        assert.match(f.css(),/line-height:1\.7222222222222223em!important/);
        assert.match(f.css(),/data-inkread-line-inline/);assert.match(f.css(),/line-height:inherit!important/);
        assert.equal(f.html(),html);assert.equal(f.body.children.length,1,'No metric text/probe survives the synchronous call');
        assert.equal(f.root.textContent,'English linked 中文代码');
    });
}

test('metric probe compensates WebView text zoom without double scaling fonts',()=>{
    const f=metricsFixture({zoom:1.6,english:12,chinese:48,natural:1.4});
    const leading=f.metrics.update(f.settings);
    assert.equal(leading,109,'76.8px CJK *1.4 =107.52, not a second1.6 zoom');
    assert.ok(Math.abs(parseFloat(f.css().match(/line-height:([0-9.]+)em/)[1]) - 109 / 19.2) < 1e-12);
});

test('uniform line length preserves larger requested CJK spacing and custom fallback height',()=>{
    const spacious=metricsFixture({english:12,chinese:48,requested:2});
    assert.equal(spacious.metrics.update(spacious.settings),96);
    assert.match(spacious.css(),/line-height:8em/);
    const fallback=metricsFixture({natural:2.3});
    assert.equal(fallback.metrics.update(fallback.settings),57,'Use actual custom font metrics, not a fixed1.4 multiplier');
});

test('metric cache invalidates for typography/font load and leaves no probe or style after destroy',()=>{
    const f=metricsFixture();f.metrics.update(f.settings);const count=f.measures(),html=f.html();
    f.metrics.update(f.settings);assert.equal(f.measures(),count);
    f.metrics.invalidate();f.metrics.update(f.settings);assert.ok(f.measures()>count);
    const second=f.measures();f.metrics.update({...f.settings,textWeight:700});assert.ok(f.measures()>second);
    assert.equal(f.html(),html);assert.equal(f.body.children.length,1);
    f.metrics.destroy();assert.equal(f.head.children.length,0);assert.equal(f.metrics.update(f.settings),0);
});

test('metric changes defer while native selection owns the document',()=>{
    const f=metricsFixture();const original=f.metrics.update(f.settings),css=f.css(),count=f.measures();
    f.setSelected(true);f.metrics.invalidate();assert.equal(f.metrics.update({...f.settings,fontSize:30}),original);
    assert.equal(f.css(),css);assert.equal(f.measures(),count);assert.equal(f.metrics.deferred(),true);
    f.setSelected(false);f.metrics.update(f.settings);assert.ok(f.measures()>count);assert.equal(f.metrics.deferred(),false);
});

test('metadata and code-only blocks are not assigned metric rules',()=>{
    const f=metricsFixture({excluded:true});assert.equal(f.metrics.update(f.settings),0);
    assert.equal(f.css(),'');assert.equal(f.block.getAttribute('data-inkread-line-block'),null);
    assert.equal(f.body.children.length,1);
});

test('metric floor restores requested inheritance at nested unmeasured heading boundaries',()=>{
    const f=metricsFixture({tag:'DIV',boundary:true});f.metrics.update(f.settings);
    assert.notEqual(f.heading.getAttribute('data-inkread-line-boundary'),null);
    assert.equal(f.heading.getAttribute('data-inkread-line-block'),null);
    assert.match(f.css(),/data-inkread-line-boundary="0"\]\{line-height:1!important;/);
    assert.ok(f.css().indexOf('line-boundary')<f.css().indexOf('line-block'),'Independent block floor rules follow boundary resets');
});

test('metric probes preserve inherited language and exclude code subtree typography',()=>{
    const f=metricsFixture({codeInside:true,language:'ja'});assert.equal(f.metrics.update(f.settings),31);
    assert.equal(f.codeSpan.getAttribute('data-inkread-line-inline'),null);
    assert.ok(f.probeLanguages.length>0);assert.ok(f.probeLanguages.every(lang=>lang==='ja'));
    assert.equal(f.body.children.length,1);
});

test('unexpected metric errors restore previous safe rules and always remove temporary text',()=>{
    const f=metricsFixture();const height=f.metrics.update(f.settings),css=f.css(),html=f.html();
    f.setFailure(true);f.metrics.invalidate();assert.equal(f.metrics.update(f.settings),height);
    assert.equal(f.css(),css);assert.equal(f.html(),html);assert.equal(f.body.children.length,1);
    f.setFailure(false);f.metrics.update(f.settings);assert.equal(f.metrics.deferred(),false);
});

test('metric cache covers height-only viewports and spacing changes',()=>{
    const f=metricsFixture();f.metrics.update(f.settings);let count=f.measures();
    f.context.innerHeight=300;f.metrics.update(f.settings);assert.ok(f.measures()>count);count=f.measures();
    f.metrics.update({...f.settings,letterSpacing:2});assert.ok(f.measures()>count);
});

test('font readiness checks selection again when its queued reflow actually runs',async()=>{
    const f=fixture();await f.flush();let selected=false;f.context.getSelection=()=>({isCollapsed:!selected});
    f.context.InkReadFormatting.apply({...defaults,fontSize:22});
    await Promise.resolve();const count=f.calls.filter(c=>c[0]==='reflow').length;
    selected=true;await f.flush();assert.equal(f.calls.filter(c=>c[0]==='reflow').length,count,
        'A native selection started after fonts.ready queued its callback must not be reflowed by that callback');
});

test('direct mixed root content uses root-scoped length without changing nested blocks',()=>{
    const f=metricsFixture({rootBlock:true,boundary:true,english:12,chinese:48});
    assert.equal(f.metrics.update(f.settings),61);
    assert.match(f.css(),/#eink-content\[data-inkread-line-block="0"\]\{line-height:/);
    assert.notEqual(f.heading.getAttribute('data-inkread-line-boundary'),null);
    assert.equal(f.body.children.length,1);
});

test('short viewport never multiplies an already clamped root line height by script ratio again',async()=>{
    const f=fixture();await f.flush();
    f.context.getComputedStyle=()=>({fontSize:'19.2px',lineHeight:'120px'});
    f.fireResize(400,200);await f.flush();
    f.context.InkReadFormatting.apply({...defaults,fontSize:12,chineseFontSize:48});await f.flush();
    const margins=f.context.InkReadFormatting.effectiveMargins();
    assert.equal(margins.top,24);assert.equal(margins.bottom,24,
        '120px of measured root leading must not become480px and erase the requested margins');
});

test('nested inherited line-height keywords cannot inherit the measured parent length',()=>{
    for(const boundaryLine of ['inherit','unset']) {
        const f=metricsFixture({tag:'DIV',boundary:true,boundaryLine});f.metrics.update(f.settings);
        assert.match(f.css(),/data-inkread-line-boundary="0"\]\{line-height:1!important;/);
    }
});

test('font reflow remains deferred and resumable when no mixed-script metric rules are needed',()=>{
    const f=metricsFixture({excluded:true});f.setSelected(true);f.metrics.invalidate();
    assert.equal(f.metrics.update(f.settings),0);assert.equal(f.metrics.deferred(),true);
    f.setSelected(false);assert.equal(f.metrics.update(f.settings),0);assert.equal(f.metrics.deferred(),false);
});


test('inline-important block and unequal inline runs receive one common length at text zoom',()=>{
    const f=metricsFixture({zoom:1.6,english:12,chinese:48,natural:1.4,important:{block:'1',link:'1.1',cjk:'.75'}});
    const html=f.html(),text=f.root.textContent,originalTextNode=f.cjk.firstChild;
    assert.equal(f.metrics.update(f.settings),109);
    for(const node of [f.block,f.link,f.cjk]) {
        assert.equal(node.style.getPropertyPriority('line-height'),'important');
        assert.match(node.style.lineHeight,/^var\(--inkread-line-\d+, /);
        assert.ok(Math.abs(parseFloat(f.computed(node).lineHeight)-109)<1e-10,'Each variable resolves against its own run size');
    }
    f.metrics.invalidate();assert.equal(f.metrics.update(f.settings),109,'A second measurement must read authored fallbacks, not its previous floor');
    assert.equal(f.html(),html);assert.equal(f.root.textContent,text);assert.equal(f.cjk.firstChild,originalTextNode);
    f.metrics.destroy();assert.equal(f.html(),html);
    assert.ok(Math.abs(parseFloat(f.computed(f.block).lineHeight)-19.2)<1e-10);
    assert.ok(Math.abs(parseFloat(f.computed(f.cjk).lineHeight)-57.6)<1e-10);
});

test('important relative source leading survives size previews and cancellation without accumulating floors',()=>{
    const f=metricsFixture({important:{block:'2em',cjk:'inherit'}}),html=f.html(),node=f.cjk.firstChild;
    assert.equal(f.metrics.update(f.settings),36);
    f.root.authored.fontSize=12;f.cjk.authored.fontSize=48;
    assert.equal(f.metrics.update({...f.settings,fontSize:12,chineseFontSize:48}),61);
    f.root.authored.fontSize=18;f.cjk.authored.fontSize=24;
    assert.equal(f.metrics.update(f.settings),36,'Restoring the original settings reads the original 2em, not the previous 61px floor');
    assert.equal(f.computed(f.block).lineHeight,'36px');assert.equal(f.computed(f.cjk).lineHeight,'36px');
    assert.equal(f.html(),html);assert.equal(f.cjk.firstChild,node);assert.equal(f.body.children.length,1);
});

test('important nested heading boundaries retain relative and inherited authored values',()=>{
    for(const [value,expected] of [['1.5em','40.5px'],['140%','37.8px'],['1.4','37.8px'],['inherit','27px'],['unset','27px'],['normal','normal']]) {
        const f=metricsFixture({tag:'DIV',boundary:true,important:{heading:value}}),html=f.html();
        f.metrics.update(f.settings);assert.equal(f.computed(f.heading).lineHeight,expected,value);
        f.metrics.invalidate();f.metrics.update(f.settings);assert.equal(f.computed(f.heading).lineHeight,expected,value);
        assert.equal(f.html(),html);assert.equal(f.heading.style.lineHeight,'var(--inkread-line-'+f.heading.getAttribute('data-inkread-line-binding')+', '+value+')');
    }
});

test('important bindings exclude code and metadata and preserve previous rules on metric failure',()=>{
    const excluded=metricsFixture({excluded:true,important:{block:'.5'}});
    assert.equal(excluded.block.style.lineHeight,'.5');assert.equal(excluded.block.getAttribute('data-inkread-line-binding'),null);
    const f=metricsFixture({codeInside:true,important:{block:'.5',codeSpan:'.25'}}),html=f.html();
    assert.equal(f.codeSpan.style.lineHeight,'.25');assert.equal(f.codeSpan.getAttribute('data-inkread-line-binding'),null);
    assert.equal(f.cjk.getAttribute('data-inkread-line-binding'),null,'Unimportant source declarations need no binding');
    const leading=f.metrics.update(f.settings),css=f.css();f.setFailure(true);f.metrics.invalidate();
    assert.equal(f.metrics.update(f.settings),leading);assert.equal(f.css(),css);assert.equal(f.html(),html);
    assert.equal(f.computed(f.block).lineHeight,'31px');assert.equal(f.body.children.length,1);
});

test('asymmetric uniform-leading envelope is reserved without iterative CSS floor growth',()=>{
    for(const rootBlock of [false,true]) for(const zoom of [1,1.6]) {
        const f=metricsFixture({english:12,chinese:48,natural:1.4,zoom,uniformExtra:11,rootBlock,important:{block:'.5',cjk:'.5'}});
        const floor=zoom===1?69:109,html=f.html();
        assert.equal(f.metrics.update(f.settings),floor+11,'Measured floor plus actual11px mixed-baseline envelope');
        assert.ok(Math.abs(parseFloat(f.computed(f.block).lineHeight)-floor)<1e-10);
        assert.ok(Math.abs(parseFloat(f.computed(f.cjk).lineHeight)-floor)<1e-10);
        f.metrics.invalidate();assert.equal(f.metrics.update(f.settings),floor+11,'The measured envelope is not fed back into the CSS floor');
        assert.equal(f.html(),html);assert.equal(f.body.children.length,1);
    }
});

test('short viewport reserves the actual uniform envelope once and retains logical anchors',async()=>{
    const metric=metricsFixture({english:12,chinese:48,uniformExtra:11,rootBlock:true});
    const height=metric.metrics.update(metric.settings);assert.equal(height,72);
    const f=fixture({metricHeight:height});await f.flush();f.context.goToPage(3);const anchor=f.anchor();
    f.context.InkReadFormatting.beginSession();f.fireResize(400,100);await f.flush();
    f.context.InkReadFormatting.apply({...defaults,fontSize:12,chineseFontSize:48});await f.flush();
    const margins=f.context.InkReadFormatting.effectiveMargins();assert.equal(margins.top,10);assert.equal(margins.bottom,10);
    assert.equal(parseFloat(f.frame.style.height),80,'72px actual envelope plus8px caret allowance, not69px or4x72px');
    assert.deepEqual(f.anchor(),anchor);
    f.context.InkReadFormatting.endSession(true,defaults);await f.flush();assert.deepEqual(f.anchor(),anchor);
});


test('boundary resets distinguish inherited authored lengths from unitless leading',()=>{
    for(const typed of [true,false]) for(const [source,expected] of [['2em',36],['36px',36],['200%',36],['2',108]]) {
        const f=metricsFixture({tag:'DIV',chinese:48,boundary:true,boundaryChild:true,typed,important:{block:source,heading:'inherit'}});
        f.metrics.update(f.settings);
        assert.ok(Math.abs(parseFloat(f.computed(f.headingRun).lineHeight)-expected)<1e-10,JSON.stringify({typed,source}));
        f.metrics.invalidate();f.metrics.update(f.settings);
        assert.ok(Math.abs(parseFloat(f.computed(f.headingRun).lineHeight)-expected)<1e-10);
    }
});

test('temporary metric typography cannot be overridden by competing author important rules',()=>{
    const f=metricsFixture({english:48,chinese:12,natural:1.4,competingRules:true,important:{block:'1',cjk:'.75'}});
    const html=f.html();assert.equal(f.metrics.update(f.settings),69,'Probe must use the source48px, not the author P rule10px');
    assert.equal(f.computed(f.block).fontSize,'48px');assert.equal(f.computed(f.block).lineHeight,'69px');
    assert.equal(f.html(),html);assert.equal(f.body.children.length,1);
});
