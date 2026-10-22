/* InkRead's live, no-reload paginated layout. The article DOM stays immutable. */
var _vw = 0, _pageStride = 0, _totalPages = 1, _currentPage = 0;
var _didFinishInitialPagination = false;
(function () {
    'use strict';
    var frame, nodes = [], media = [], settings = window._inkreadInitialFormatting;
    var anchor = null, sessionAnchor = null, sessionOpen = false, revision = 0;
    var resizeTimer = 0, reflowFrame = 0, destroyed = false;
    var margins = { left: 0, right: 0, top: 0, bottom: 0 };
    var fontCss = '', pendingAnchor = null;

    function notify(name) {
        try {
            if (name === 'ready') Android.onInitialPaginationReady(window._inkreadGeneration, _totalPages);
            else Android.onRepaginated(window._inkreadGeneration, _totalPages, _currentPage);
        } catch (ignored) {}
    }
    function textRange(node, start, end) {
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
    window.goToPage = function (n) {
        if (!frame || sessionOpen) return;
        var previous = _currentPage;
        placePage(n);
        // Native onRepaginated echoes the same page. It must not replace the
        // canonical anchor with a different first word on that reflowed page.
        if (previous !== _currentPage || !anchor) anchor = captureAnchor();
    };
    function layoutViewport() {
        // Never round the CSS viewport: integer innerWidth drifts at 420dpi.
        _vw = document.documentElement.getBoundingClientRect().width || window.innerWidth || 360;
        var vh = window.innerHeight || document.documentElement.clientHeight || 640;
        _pageStride = _vw;
        // Retain a usable line/column on tiny split views or while an IME is open.
        var minimumWidth = Math.max(32, settings.fontSize * 1.5);
        var horizontalScale = Math.min(1, Math.max(0, _vw - minimumWidth) / Math.max(1, settings.marginLeft + settings.marginRight));
        var minimumLine = Math.max(32, settings.fontSize * Math.max(settings.lineHeight, 1.4) + 8);
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
    }
    function scheduleReflow() {
        if (!_didFinishInitialPagination || destroyed) return;
        // Font/image layout can already have changed by the event. Use the
        // anchor cached at page navigation, never recapture after that change.
        pendingAnchor = sessionOpen ? sessionAnchor : anchor;
        if (reflowFrame) cancelAnimationFrame(reflowFrame);
        reflowFrame = requestAnimationFrame(function () {
            reflowFrame = 0;
            repaginate(pendingAnchor);
        });
    }
    function setStyle(value) {
        settings = value;
        var style = document.documentElement.style;
        var variables = {
            'font-size': value.fontSize + 'px', 'line-height': value.lineHeight,
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
            if (token === revision && !destroyed) scheduleReflow();
        }, function () {});
    }
    window.InkReadFormatting = {
        documentId: window._inkreadGeneration,
        captureAnchor: captureAnchor,
        currentAnchor: function () { return anchor; },
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
    function finishInitialPagination() {
        if (_didFinishInitialPagination || destroyed) return;
        _didFinishInitialPagination = true;
        layoutViewport();
        recountPages();
        placePage(0);
        anchor = captureAnchor();
        frame.style.visibility = 'visible';
        notify('ready');
    }
    function initialize() {
        frame = document.getElementById('eink-content');
        var walker = document.createTreeWalker(frame, NodeFilter.SHOW_TEXT, {
            acceptNode: function (node) {
                var parent = node.parentElement;
                return parent && !/^(SCRIPT|STYLE|NOSCRIPT)$/.test(parent.tagName) && /\S/.test(node.data) ?
                    NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
            }
        });
        var node;
        while ((node = walker.nextNode())) nodes.push(node);
        media = Array.prototype.slice.call(frame.querySelectorAll('img,svg,video,table'));
        setStyle(settings);
        layoutViewport();
        frame.getBoundingClientRect(); // Start font loading before observing the readiness promise.
        // Includes ordinary images arriving long after initial font readiness.
        frame.addEventListener('load', scheduleReflow, true);
        frame.addEventListener('error', scheduleReflow, true);
        if (document.fonts) document.fonts.addEventListener('loadingdone', scheduleReflow);
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
    window.addEventListener('pagehide', function () {
        destroyed = true;
        clearTimeout(resizeTimer);
        if (reflowFrame) cancelAnimationFrame(reflowFrame);
        window.removeEventListener('resize', onResize);
        if (frame) {
            frame.removeEventListener('load', scheduleReflow, true);
            frame.removeEventListener('error', scheduleReflow, true);
        }
        if (document.fonts) document.fonts.removeEventListener('loadingdone', scheduleReflow);
    }, { once: true });
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, { once: true });
    else initialize();
})();
