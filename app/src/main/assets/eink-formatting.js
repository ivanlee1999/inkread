/* Native selection hit-testing uses line boxes, not overflowing glyph rectangles.
 * Keep mixed-script lines at least as tall as their real loaded font metrics.
 * The requested preference is unchanged. Measurements and rules live outside the
 * immutable article, and every temporary probe is removed in the same JS turn.
 */
(function () {
    'use strict';
    var EXCLUDE = /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE|PRE|CODE|MATH|SVG|TEXTAREA|SELECT|OPTION|INPUT|IMG|VIDEO|IFRAME|OBJECT|EMBED|CANVAS)$/;
    var BLOCK = /^(P|LI|BLOCKQUOTE|TD|TH|H[1-6]|DT|DD|DIV|SECTION|ARTICLE|MAIN)$/;
    var INLINE = 'a,b,strong,i,em,s,del,u,mark,small,sub,sup,span,abbr,cite,q,time';
    var TYPOGRAPHY = ['fontFamily','fontWeight','fontStyle','fontStretch','fontVariant','fontFeatureSettings',
        'fontKerning','fontVariationSettings','letterSpacing','wordSpacing','textTransform','verticalAlign','direction'];
    function tag(node) { return String(node.tagName || '').toUpperCase(); }
    function number(value, fallback) { var result = parseFloat(value); return isFinite(result) ? result : fallback; }
    function eligible(element, root) {
        for (var n = element; n && n !== root; n = n.parentElement) {
            if (EXCLUDE.test(tag(n)) || (n.classList && n.classList.contains('eink-metadata')) ||
                n.getAttribute('aria-hidden') === 'true') return false;
        }
        return !!element;
    }
    function isBlock(element) {
        var display = window.getComputedStyle(element).display;
        return display ? /^(block|flow-root|list-item|table|flex|grid|inline-block|inline-flex|inline-grid)/.test(display) : BLOCK.test(tag(element));
    }
    function closestBlock(element, root) {
        for (var n = element; n && n !== root; n = n.parentElement) if (isBlock(n)) return n;
        return root;
    }
    function create(root, model) {
        var document = root.ownerDocument || window.document, blocks = [], boundaries = [], seen = new WeakMap(), boundarySeen = new WeakMap();
        model.nodes.forEach(function (record) {
            if (!eligible(record.parentElement, root) || !record.fragments.some(function (piece) {
                var p = piece.node.parentElement;
                return p && p.classList && p.classList.contains('eink-script-cjk');
            })) return;
            var block = closestBlock(record.parentElement, root);
            if (seen.has(block)) return;
            seen.set(block, true);
            // Assigned once, before pagination is ready. Previews change only the
            // separate stylesheet, never article attributes or text nodes.
            block.setAttribute('data-inkread-line-block', String(blocks.length));
            function markInline(parent) {
                for (var child = parent.firstChild; child; child = child.nextSibling) {
                    if (child.nodeType !== 1 || EXCLUDE.test(tag(child)) || isBlock(child) || !eligible(child, root)) continue;
                    if (INLINE.split(',').indexOf(tag(child).toLowerCase()) !== -1) child.setAttribute('data-inkread-line-inline', '');
                    markInline(child);
                }
            }
            markInline(block);
            blocks.push(block);
        });
        blocks.forEach(function (block) {
            function findBoundaries(parent) {
                for (var child = parent.firstChild; child; child = child.nextSibling) {
                    if (child.nodeType !== 1) continue;
                    if (isBlock(child)) {
                        if (!boundarySeen.has(child)) {
                            boundarySeen.set(child, true);
                            child.setAttribute('data-inkread-line-boundary', String(boundaries.length));
                            boundaries.push(child);
                        }
                    } else findBoundaries(child);
                }
            }
            findBoundaries(block);
        });
        var style = document.createElement('style');
        style.setAttribute('data-inkread-line-metrics', '');
        (document.head || document.body).appendChild(style);
        var cacheKey = '', lastHeight = 0, epoch = 0, deferred = false, disposed = false;
        function selectionActive() {
            var selection = window.getSelection && window.getSelection();
            return selection && !selection.isCollapsed;
        }
        function update(settings) {
            if (disposed) return 0;
            if (selectionActive()) { deferred = true; return lastHeight; }
            if (!blocks.length) { deferred = false; return 0; }
            var frameStyle = window.getComputedStyle(root);
            var key = JSON.stringify([settings.fontSize, settings.chineseFontSize, settings.lineHeight,
                settings.textWeight, settings.fontCss, settings.letterSpacing, settings.wordSpacing, settings.alignment,
                frameStyle.fontSize, window.innerWidth, window.innerHeight, epoch]);
            if (key === cacheKey && !deferred) return lastHeight;
            deferred = false;
            // Read requested styles without our previous floor. No frame can be
            // painted between this removal and the synchronous replacement.
            var previousCss = style.textContent;
            style.textContent = '';
            var host = document.createElement('div'), rules = [], maximum = 0;
            host.setAttribute('aria-hidden', 'true'); host.setAttribute('inert', '');
            host.style.cssText = 'position:fixed;left:-100000px;top:0;visibility:hidden;pointer-events:none;' +
                'width:max-content;max-width:none;height:auto;margin:0;padding:0;border:0;contain:layout style;';
            document.body.appendChild(host);
            try {
                var frameSize = number(window.getComputedStyle(root).fontSize, settings.fontSize);
                boundaries.forEach(function (boundary) {
                    var computed = window.getComputedStyle(boundary), size = number(computed.fontSize, frameSize);
                    var declared = boundary.style.lineHeight;
                    if (/^(inherit|unset|revert|revert-layer)$/.test(declared)) declared = '';
                    var leading = declared || (computed.lineHeight === 'normal' ? 'normal' : String(number(computed.lineHeight, size * settings.lineHeight) / size));
                    rules.push('#eink-content [data-inkread-line-boundary="' + boundary.getAttribute('data-inkread-line-boundary') +
                        '"]{line-height:' + leading + '!important;}');
                });
                blocks.forEach(function (block) {
                    var sourceStyle = window.getComputedStyle(block), blockSize = number(sourceStyle.fontSize, frameSize);
                    if (!(blockSize > 0) || sourceStyle.display === 'none') return;
                    var requested = number(sourceStyle.lineHeight, blockSize * settings.lineHeight);
                    var probe = document.createElement(block.tagName), textCount = 0;
                    for (var ancestor = block; ancestor; ancestor = ancestor.parentElement) {
                        var language = ancestor.getAttribute('lang') || ancestor.getAttribute('xml:lang');
                        if (language) { probe.setAttribute('lang', language); break; }
                    }
                    probe.style.cssText = 'display:block;position:relative;width:max-content;max-width:none;min-width:0;' +
                        'height:auto;min-height:0;max-height:none;margin:0;padding:0;border:0;white-space:nowrap;' +
                        'text-indent:0;column-count:auto;column-width:auto;overflow:visible;line-height:normal;';
                    function copyType(from, to, parentSize) {
                        var computed = window.getComputedStyle(from), size = number(computed.fontSize, parentSize);
                        TYPOGRAPHY.forEach(function (name) { if (computed[name]) to.style[name] = computed[name]; });
                        to.style.fontSize = (size / parentSize) + 'em';
                        to.style.lineHeight = 'normal';
                        requested = Math.max(requested, number(computed.lineHeight, size * settings.lineHeight));
                        return size;
                    }
                    copyType(block, probe, blockSize);
                    // WebView text zoom also applies to probes. Start with the
                    // authored CSS size, then verify against the live block.
                    probe.style.fontSize = (settings.fontSize * blockSize / frameSize) + 'px';
                    function copyChildren(from, to, parentSize) {
                        for (var child = from.firstChild; child; child = child.nextSibling) {
                            if (child.nodeType === 3) {
                                to.appendChild(document.createTextNode(child.data));
                                if (/\S/.test(child.data)) textCount++;
                            } else if (child.nodeType === 1 && !EXCLUDE.test(tag(child)) && tag(child) !== 'BR' &&
                                !isBlock(child) && eligible(child, root)) {
                                var childStyle = window.getComputedStyle(child);
                                if (childStyle.display === 'none') continue;
                                var clone = document.createElement(child.tagName);
                                var size = copyType(child, clone, parentSize);
                                clone.style.display = childStyle.display;
                                if (child.getAttribute('lang')) clone.setAttribute('lang', child.getAttribute('lang'));
                                copyChildren(child, clone, size); to.appendChild(clone);
                            }
                        }
                    }
                    copyChildren(block, probe, blockSize);
                    if (!textCount) return;
                    host.appendChild(probe);
                    var actualSize = number(window.getComputedStyle(probe).fontSize, blockSize);
                    if (actualSize > 0 && Math.abs(actualSize - blockSize) > .01) {
                        probe.style.fontSize = (number(probe.style.fontSize, settings.fontSize) * blockSize / actualSize) + 'px';
                    }
                    var box = probe.getBoundingClientRect(), range = document.createRange();
                    range.selectNodeContents(probe);
                    var glyph = range.getBoundingClientRect();
                    var natural = Math.max(box.height, glyph.height, Math.max(box.bottom, glyph.bottom) - Math.min(box.top, glyph.top));
                    // Bound duplicate text memory to one inline context, even
                    // for articles with thousands of mixed-script paragraphs.
                    probe.remove();
                    if (!(natural > 0) || !isFinite(natural)) return;
                    // Round outward for native integer caret bounds. This is a
                    // measured length, not an assumed font-size multiplier.
                    var leading = Math.max(requested, Math.ceil(natural) + 1);
                    maximum = Math.max(maximum, leading);
                    var selector = '#eink-content[data-inkread-line-block="' + block.getAttribute('data-inkread-line-block') + '"]';
                    if (block !== root) selector = '#eink-content [data-inkread-line-block="' + block.getAttribute('data-inkread-line-block') + '"]';
                    rules.push(selector + '{line-height:' + (leading / blockSize) + 'em!important;}');
                    // A length is inherited unchanged by the differently sized
                    // script runs. Unitless leading would multiply it again.

                });
            } catch (error) {
                // A metric failure must not remove the last safe layout or
                // prevent the article from opening. Retry on a later update.
                style.textContent = previousCss; deferred = true;
                if (window.console && console.warn) console.warn('InkRead font metrics unavailable', error);
                return lastHeight;
            } finally { host.remove(); }
            if (rules.length) rules.push('#eink-content [data-inkread-line-inline]{line-height:inherit!important;}');
            style.textContent = rules.join('\n');
            cacheKey = key; lastHeight = maximum;
            return maximum;
        }
        return { update: update, invalidate: function () { epoch++; },
            deferred: function () { return deferred; },
            destroy: function () { disposed = true; style.remove(); } };
    }
    window.InkReadLineMetrics = { create: create };
})();

/* Live paginated layout. Logical article nodes stay immutable across script-size reflow. */
var _vw = 0, _pageStride = 0, _totalPages = 1, _currentPage = 0;
var _didFinishInitialPagination = false;
(function () {
    'use strict';
    var frame, nodes = [], media = [], settings = window._inkreadInitialFormatting;
    var anchor = null, sessionAnchor = null, sessionOpen = false, revision = 0;
    var resizeTimer = 0, reflowFrame = 0, destroyed = false;
    var margins = { left: 0, right: 0, top: 0, bottom: 0 };
    var fontCss = '', pendingAnchor = null, columnProbe = null;
    var readingIdentity = window._inkreadReadingIdentity || null;
    var lastAppliedCommandId = 0, scriptFonts = null, lineMetrics = null, measuredLeading = 0;

    function notify(name) {
        try {
            if (name === 'ready') Android.onInitialPaginationReady(window._inkreadGeneration, _totalPages, _currentPage, lastAppliedCommandId);
            else Android.onRepaginated(window._inkreadGeneration, _totalPages, _currentPage, lastAppliedCommandId);
        } catch (ignored) {}
    }
    // Persist immutable DOM positions, never CSS page numbers. The identity includes
    // storage revision plus rendered metadata/body fingerprint, not typography.
    function validAnchor(value) {
        if (!value || typeof value.offset !== 'number' || value.offset < 0 || value.offset % 1) return false;
        if (value.element !== undefined) return typeof value.element === 'number' &&
            value.element >= 0 && value.element % 1 === 0 && !!media[value.element] && value.offset === 0;
        var node = nodes[value.node];
        return typeof value.node === 'number' && value.node >= 0 && value.node % 1 === 0 &&
            !!node && value.offset < node.length &&
            !(value.offset > 0 && /[\uDC00-\uDFFF]/.test(node.data.charAt(value.offset)));
    }
    function exportReadingAnchor() {
        if (!readingIdentity || !_didFinishInitialPagination || destroyed) return null;
        var value = sessionOpen ? sessionAnchor : anchor;
        if (!validAnchor(value)) return null;
        return JSON.stringify({ version: 1, itemId: readingIdentity.itemId,
            contentRevision: readingIdentity.contentRevision,
            documentFingerprint: readingIdentity.documentFingerprint, anchor: value });
    }
    function publishReadingAnchor() {
        var json = exportReadingAnchor();
        if (json) try { Android.onReadingAnchor(window._inkreadGeneration,
            readingIdentity.itemId, readingIdentity.contentRevision, json); } catch (ignored) {}
        return json;
    }
    function initialReadingAnchor() {
        var raw = window._inkreadInitialReadingAnchor, status = 'new', value = null;
        if (readingIdentity && raw) {
            try {
                var saved = JSON.parse(raw);
                if (saved.version !== 1 || saved.itemId !== readingIdentity.itemId ||
                    saved.contentRevision !== readingIdentity.contentRevision ||
                    saved.documentFingerprint !== readingIdentity.documentFingerprint) status = 'changed';
                else if (validAnchor(saved.anchor)) { value = saved.anchor; status = 'restored'; }
                else status = 'invalid';
            } catch (ignored) { status = 'invalid'; }
        }
        if (readingIdentity) try { Android.onReadingAnchorRestore(window._inkreadGeneration, status); } catch (ignored) {}
        return value;
    }
    function textRange(node, start, end) {
        if (scriptFonts) return scriptFonts.range(node, Math.min(node.length, Math.max(0, start)),
            Math.min(node.length, Math.max(start, end)));
        var range = document.createRange();
        range.setStart(node, Math.min(node.length, Math.max(0, start)));
        range.setEnd(node, Math.min(node.length, Math.max(start, end)));
        return range;
    }
    function rects(node) { return textRange(node, 0, node.length).getClientRects(); }
    function visible(rect) {
        return rect.width > 0 && rect.height > 0 && rect.right > margins.left + .1 &&
            rect.left < _vw - margins.right - .1 && rect.bottom > margins.top + .1 &&
            rect.top < window.innerHeight - margins.bottom - .1;
    }
    function nodeVisible(index) {
        var list = rects(nodes[index]);
        for (var i = 0; i < list.length; i++) if (visible(list[i])) return true;
        return false;
    }
    function captureAnchor() {
        if (!frame || !nodes.length) return captureMedia() || { node: 0, offset: 0 };
        // Text nodes are in DOM reading order. Binary search the first node whose
        // last fragment reaches this page; scan past hidden/non-rendered nodes.
        var low = 0, high = nodes.length;
        while (low < high) {
            var mid = (low + high) >> 1, list = rects(nodes[mid]);
            if (list.length && list[list.length - 1].right <= margins.left + .1) low = mid + 1;
            else high = mid;
        }
        for (var index = low; index < nodes.length; index++) {
            var boxes = rects(nodes[index]);
            if (!boxes.length) continue;
            if (boxes[0].left >= _vw - margins.right && boxes[0].width > 0) break;
            if (!nodeVisible(index)) continue;
            var node = nodes[index], start = 0, end = Math.max(0, node.length - 1);
            while (start < end) {
                var offset = (start + end) >> 1;
                var r = textRange(node, offset, offset + 1).getBoundingClientRect();
                if (r.right <= margins.left + .1 || r.bottom <= margins.top + .1) start = offset + 1;
                else end = offset;
            }
            // Avoid anchoring to collapsed whitespace or the second UTF-16 half
            // of a supplementary glyph. Native Range offsets are UTF-16 offsets.
            while (start < node.length && (/\s/.test(node.data.charAt(start)) ||
                !visible(textRange(node, start, start + 1).getBoundingClientRect()))) start++;
            if (start < node.length) {
                if (start > 0 && /[\uDC00-\uDFFF]/.test(node.data.charAt(start))) start--;
                return { node: index, offset: start };
            }
        }
        return captureMedia() || anchor || { node: 0, offset: 0 };
    }
    function captureMedia() {
        for (var i = 0; i < media.length; i++) {
            if (visible(media[i].getBoundingClientRect())) return { element: i, offset: 0 };
        }
        return null;
    }
    function anchorRect(value) {
        if (!value) return null;
        if (value.element !== undefined) return media[value.element] && media[value.element].getBoundingClientRect();
        var node = nodes[value.node];
        if (!node || !node.length) return null;
        var offset = Math.min(node.length - 1, Math.max(0, value.offset));
        return textRange(node, offset, offset + 1).getBoundingClientRect();
    }
    function placePage(n) {
        _currentPage = Math.min(_totalPages - 1, Math.max(0, Math.round(n) || 0));
        frame.style.transform = 'translateX(-' + (_currentPage * _pageStride) + 'px)';
    }
    window.goToPage = function (n, commandId, expectedGeneration) {
        if (!frame || sessionOpen) return;
        if (expectedGeneration !== undefined && expectedGeneration !== window._inkreadGeneration) return;
        var hasCommandId = commandId !== undefined;
        if (hasCommandId && (typeof commandId !== 'number' || !isFinite(commandId) ||
            commandId % 1 || commandId < lastAppliedCommandId)) return;
        // A retried command may arrive after a valid reflow moved its anchor to
        // another page. Acknowledge current geometry, never replay its old target.
        if (hasCommandId && commandId === lastAppliedCommandId) {
            notify('reflow');
            return;
        }
        var previous = _currentPage;
        placePage(n);
        if (hasCommandId) lastAppliedCommandId = commandId;
        // A same-page command must not replace the canonical anchor with a
        // different first word on that reflowed page.
        if (previous !== _currentPage || !anchor) {
            anchor = captureAnchor();
            // A font/image/resize event may already have queued a reflow with
            // the previous page's anchor. An accepted turn supersedes it; that
            // later animation frame must not undo this newer reading position.
            pendingAnchor = anchor;
            publishReadingAnchor();
        }
        // Even a same-page or clamped command acknowledges its handled ID and
        // latest page count. Native can then discard an older queued reflow
        // without losing the authoritative result of its newest page command.
        if (hasCommandId) notify('reflow');
    };
    function layoutViewport() {
        if (lineMetrics) measuredLeading = lineMetrics.update(settings);
        // Never round the CSS viewport: integer innerWidth drifts at 420dpi.
        _vw = document.documentElement.getBoundingClientRect().width || window.innerWidth || 360;
        var vh = window.innerHeight || document.documentElement.clientHeight || 640;
        // Retain a usable line/column on tiny split views or while an IME is open.
        // Android WebView text zoom can make computed type larger than the
        // requested CSS value (e.g. 18px becomes 28.8px at system scale 1.6).
        // Reserve space for the actual rendered line/glyph, not its raw setting.
        var typography = window.getComputedStyle(frame);
        var effectiveFontSize = parseFloat(typography.fontSize) || settings.fontSize;
        // The root itself may carry an already measured mixed-script floor.
        // Scale the requested base leading, never that floor a second time.
        var effectiveLineHeight = effectiveFontSize * settings.lineHeight;
        // CJK may be larger than the English base even when system text zoom is active.
        var largestScriptScale = Math.max(1, (settings.chineseFontSize || settings.fontSize) / settings.fontSize);
        effectiveFontSize *= largestScriptScale;
        effectiveLineHeight *= largestScriptScale;
        var minimumWidth = Math.max(32, effectiveFontSize * 1.5);
        var horizontalScale = Math.min(1, Math.max(0, _vw - minimumWidth) / Math.max(1, settings.marginLeft + settings.marginRight));
        var minimumLine = Math.max(32, Math.max(effectiveLineHeight, effectiveFontSize * 1.4, measuredLeading) + 8);
        var verticalScale = Math.min(1, Math.max(0, vh - minimumLine) / Math.max(1, settings.marginTop + settings.marginBottom));
        margins = {
            left: settings.marginLeft * horizontalScale, right: settings.marginRight * horizontalScale,
            top: settings.marginTop * verticalScale, bottom: settings.marginBottom * verticalScale
        };
        var width = Math.max(1, _vw - margins.left - margins.right);
        var height = Math.max(1, vh - margins.top - margins.bottom);
        frame.style.left = margins.left + 'px';
        frame.style.top = margins.top + 'px';
        frame.style.width = width + 'px';
        frame.style.height = height + 'px';
        frame.style.columnWidth = width + 'px';
        frame.style.columnGap = (margins.left + margins.right) + 'px';
        document.documentElement.style.setProperty('--reader-column-height', height + 'px');
        measurePageStride(width, margins.left + margins.right);
    }
    function measurePageStride(width, gap) {
        // Blink quantizes column width and gap independently to layout units.
        // Their actual sum can differ from the fractional viewport by a fraction
        // of a pixel, accumulating into an entire wrong column in long articles.
        // Measure two empty columns outside the immutable article. This works for
        // text, unbroken code, image-only and single-page documents alike.
        if (!columnProbe) {
            columnProbe = document.createElement('div');
            columnProbe.setAttribute('aria-hidden', 'true');
            columnProbe.setAttribute('inert', '');
            columnProbe.style.cssText = 'position:absolute;visibility:hidden;pointer-events:none;left:0;top:0;' +
                'height:1px;margin:0;padding:0;border:0;overflow:hidden;box-sizing:border-box;column-fill:auto;' +
                'column-count:auto;direction:ltr;writing-mode:horizontal-tb;';
            for (var i = 0; i < 2; i++) {
                var marker = document.createElement('div');
                marker.style.cssText = 'display:block;width:1px;height:1px;margin:0;padding:0;border:0;';
                if (i) marker.style.breakBefore = 'column';
                columnProbe.appendChild(marker);
            }
            document.body.appendChild(columnProbe);
        }
        // CSSOM getters shorten decimal declarations (364.1904907 -> 364.19).
        // Re-parsing them can lose another layout unit at fractional densities.
        // Feed the probe the same original numbers assigned to the article.
        columnProbe.style.width = width + 'px';
        columnProbe.style.columnWidth = width + 'px';
        columnProbe.style.columnGap = gap + 'px';
        var measured = columnProbe.children[1].getBoundingClientRect().left -
            columnProbe.children[0].getBoundingClientRect().left;
        _pageStride = isFinite(measured) && measured > 0 ? measured : _vw;
    }
    function recountPages() {
        var extent = frame.scrollWidth + margins.left + margins.right;
        // scrollWidth is integer-rounded, hence the one-pixel tolerance. Unlike
        // round(scrollWidth/stride), this also works with large unequal margins.
        var count = Math.ceil((extent - 1) / Math.max(1, _pageStride));
        // Large type in a compact preview can legitimately exceed 500 pages.
        // Do not send the reader to page one merely because layout is spacious.
        _totalPages = !isFinite(count) ? 1 : Math.min(2147483647, Math.max(1, count));
    }
    function repaginate(value, reason) {
        if (destroyed || !_didFinishInitialPagination || !frame) return;
        frame.style.transform = 'none';
        layoutViewport();
        recountPages();
        var rect = anchorRect(value);
        placePage(rect ? Math.floor(Math.max(0, rect.left - margins.left + .5) / _pageStride) : _currentPage);
        anchor = value || captureAnchor();
        notify(reason || 'reflow');
        publishReadingAnchor();
    }
    function scheduleReflow(metricOnly) {
        metricOnly = metricOnly === true;
        if (!_didFinishInitialPagination || destroyed) return;
        // Font/image layout can already have changed by the event. Use the
        // anchor cached at page navigation, never recapture after that change.
        pendingAnchor = sessionOpen ? sessionAnchor : anchor;
        if (reflowFrame) cancelAnimationFrame(reflowFrame);
        reflowFrame = requestAnimationFrame(function () {
            reflowFrame = 0;
            var selection = metricOnly && window.getSelection && window.getSelection();
            if (selection && !selection.isCollapsed) {
                if (lineMetrics) lineMetrics.update(settings);
                return;
            }
            repaginate(pendingAnchor);
        });
    }
    function setStyle(value) {
        settings = value;
        var style = document.documentElement.style;
        var variables = {
            'font-size': value.fontSize + 'px', 'line-height': value.lineHeight,
            'cjk-scale': ((value.chineseFontSize || value.fontSize) / value.fontSize) + 'em',
            'letter-spacing': value.letterSpacing + 'px', 'word-spacing': value.wordSpacing + 'em',
            'text-color': 'rgb(' + Math.round(255 * (1 - value.textDarkness)) + ',' + Math.round(255 * (1 - value.textDarkness)) + ',' + Math.round(255 * (1 - value.textDarkness)) + ')',
            'text-weight': value.textWeight, 'paragraph-gap': value.paragraphGap + 'em',
            'paragraph-indent': value.paragraphIndent + 'em', 'alignment': value.alignment,
            'image-contrast': value.imageContrast, 'image-grayscale': value.imageGrayscale ? 1 : 0
        };
        Object.keys(variables).forEach(function (key) { style.setProperty('--reader-' + key, variables[key]); });
        if (fontCss !== value.fontCss) {
            fontCss = value.fontCss;
            document.getElementById('eink-font-faces').textContent = fontCss;
        }
    }
    function apply(value) {
        if (!value) return;
        var token = ++revision;
        var target = sessionOpen ? sessionAnchor : (anchor || captureAnchor());
        setStyle(value);
        if (_didFinishInitialPagination) repaginate(target);
        // Force font selection/layout before reading document.fonts.ready.
        if (frame) frame.getBoundingClientRect();
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(function () {
            if (token === revision && !destroyed) {
                if (lineMetrics) lineMetrics.invalidate();
                scheduleMetricReflow();
            }
        }, function () {});
    }
    window.InkReadFormatting = {
        documentId: window._inkreadGeneration,
        captureAnchor: captureAnchor,
        currentAnchor: function () { return anchor; },
        exportReadingAnchor: exportReadingAnchor,
        refreshLocalImages: function (token) {
            if (!frame || destroyed) return;
            var images = frame.querySelectorAll('img[src^="https://inkread.local/saved-library-assets/"]');
            for (var i = 0; i < images.length; i++) {
                var src = images[i].getAttribute('src').split('?')[0];
                images[i].setAttribute('src', src + '?revision=' + encodeURIComponent(token));
            }
        },
        publishReadingAnchor: publishReadingAnchor,
        effectiveMargins: function () { return margins; },
        beginSession: function () {
            if (!sessionOpen) {
                sessionAnchor = anchor || captureAnchor();
                sessionOpen = true;
            }
            return sessionAnchor;
        },
        apply: apply,
        endSession: function (cancel, value) {
            // Both dismissal paths retain the pre-panel reading location. Cancel
            // also supplies the original formatting snapshot from native state.
            if (sessionOpen) anchor = sessionAnchor;
            sessionOpen = false;
            sessionAnchor = null;
            apply(value);
            scheduleReflow();
        }
    };
    function scheduleMetricReflow() {
        if (lineMetrics) {
            var selection = window.getSelection && window.getSelection();
            if (selection && !selection.isCollapsed) { lineMetrics.update(settings); return; }
        }
        scheduleReflow(true);
    }
    function onFontLoadingDone() {
        if (lineMetrics) lineMetrics.invalidate();
        scheduleMetricReflow();
    }
    function onMetricSelectionChange() {
        if (lineMetrics && lineMetrics.deferred()) scheduleMetricReflow();
    }
    function finishInitialPagination() {
        if (_didFinishInitialPagination || destroyed) return;
        _didFinishInitialPagination = true;
        if (lineMetrics) lineMetrics.invalidate();
        layoutViewport();
        recountPages();
        var restored = initialReadingAnchor();
        var rect = anchorRect(restored);
        placePage(rect ? Math.floor(Math.max(0, rect.left - margins.left + .5) / _pageStride) : 0);
        anchor = restored || captureAnchor();
        // Do not emit a transient initial page-zero anchor before restore. Native
        // initializes its controller from this already-restored page.
        frame.style.visibility = 'visible';
        notify('ready');
    }
    function initialize() {
        frame = document.getElementById('eink-content');
        scriptFonts = window.InkReadScriptFonts && InkReadScriptFonts.install(frame);
        if (scriptFonts) nodes = scriptFonts.readingNodes;
        else {
            var walker = document.createTreeWalker(frame, NodeFilter.SHOW_TEXT, {
                acceptNode: function (node) {
                    var parent = node.parentElement;
                    return parent && !/^(SCRIPT|STYLE|NOSCRIPT)$/.test(parent.tagName) && /\S/.test(node.data) ?
                        NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
                }
            });
            var node;
            while ((node = walker.nextNode())) nodes.push(node);
        }
        if (scriptFonts) lineMetrics = InkReadLineMetrics.create(frame, scriptFonts);
        document.addEventListener('selectionchange', onMetricSelectionChange);
        media = Array.prototype.slice.call(frame.querySelectorAll('img,svg,video,table'));
        setStyle(settings);
        layoutViewport();
        frame.getBoundingClientRect(); // Start font loading before observing the readiness promise.
        // Includes ordinary images arriving long after initial font readiness.
        frame.addEventListener('load', scheduleReflow, true);
        frame.addEventListener('error', scheduleReflow, true);
        if (document.fonts) document.fonts.addEventListener('loadingdone', onFontLoadingDone);
        var finish = function () { requestAnimationFrame(finishInitialPagination); };
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(finish, finish);
        else finish();
        setTimeout(finish, 1500);
    }
    function onResize() {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(scheduleReflow, 80);
    }
    window.addEventListener('resize', onResize);
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'hidden') publishReadingAnchor();
    });
    window.addEventListener('pagehide', function () {
        publishReadingAnchor();
        destroyed = true;
        clearTimeout(resizeTimer);
        if (reflowFrame) cancelAnimationFrame(reflowFrame);
        window.removeEventListener('resize', onResize);
        if (frame) {
            frame.removeEventListener('load', scheduleReflow, true);
            frame.removeEventListener('error', scheduleReflow, true);
        }
        if (document.fonts) document.fonts.removeEventListener('loadingdone', onFontLoadingDone);
        if (columnProbe) columnProbe.remove();
        if (lineMetrics) lineMetrics.destroy();
        document.removeEventListener('selectionchange', onMetricSelectionChange);
    }, { once: true });
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, { once: true });
    else initialize();
})();
