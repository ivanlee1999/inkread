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

function fixture({layoutUnit=0, layoutScale=1, nodeCount=30, serializeStyles=false, identity=null, savedAnchor=null, mediaCount=0}={}) {
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
    const document={readyState:'complete',visibilityState:'visible',addEventListener:(name,cb)=>documentListeners.set(name,cb),createElement,body:{appendChild:child=>bodyChildren.push(child)}, documentElement:{style:{setProperty:(name,value)=>variables[name]=String(value)},
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
    vm.createContext(context);vm.runInContext(runtime,context);
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
