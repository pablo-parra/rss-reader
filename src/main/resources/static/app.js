// Shows a loading overlay while the browser waits for a slow page: any link or form with a data-loading message.
(function () {
    function show(message) {
        hide();
        var overlay = document.createElement('div');
        overlay.className = 'loading-overlay';
        overlay.setAttribute('role', 'status');
        overlay.setAttribute('aria-live', 'polite');
        var spinner = document.createElement('div');
        spinner.className = 'spinner';
        var text = document.createElement('p');
        text.textContent = message || 'Loading...';
        overlay.appendChild(spinner);
        overlay.appendChild(text);
        document.body.appendChild(overlay);
    }

    function hide() {
        document.querySelectorAll('.loading-overlay').forEach(function (el) { el.remove(); });
    }

    document.addEventListener('click', function (event) {
        var link = event.target.closest && event.target.closest('a[data-loading]');
        if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey
                || event.shiftKey || link.target === '_blank') {
            return;
        }
        show(link.getAttribute('data-loading'));
    });

    // A form with data-confirm asks first, and does nothing when the user cancels.
    document.addEventListener('submit', function (event) {
        var form = event.target;
        if (form.hasAttribute && form.hasAttribute('data-confirm') && !window.confirm(form.getAttribute('data-confirm'))) {
            event.preventDefault();
        }
    });

    document.addEventListener('submit', function (event) {
        var form = event.target;
        if (!form.hasAttribute || !form.hasAttribute('data-loading') || event.defaultPrevented) {
            return;
        }
        show(form.getAttribute('data-loading'));
        var button = form.querySelector('button');
        if (button) {
            setTimeout(function () { button.disabled = true; }, 0);
        }
    });

    // Coming back with the browser's back button must not show a stale overlay.
    window.addEventListener('pageshow', hide);
})();
