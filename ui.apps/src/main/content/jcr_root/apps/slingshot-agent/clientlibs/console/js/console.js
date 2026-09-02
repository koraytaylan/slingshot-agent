/*
 * SPDX-License-Identifier: MIT OR Apache-2.0
 * Copyright 2026 Koray Taylan Davgana
 *
 * The only script this product ships. It does one thing the server cannot: it follows an operation
 * that is still running, over the same event route the client uses rather than a second stream, so
 * what an operator watching a console sees is what a client following the same operation sees.
 *
 * Everything else on this page is server-rendered. That is not minimalism for its own sake - it is
 * what makes the console provable without a browser: a response the server already determined is a
 * response a test can assert over HTTP.
 */
(function () {
    'use strict';

    /** Where an operation's events are followed, which is the route the client already uses. */
    var EVENTS = '/bin/slingshot/agent/events';

    /** What the tail marks a line with when the stream ends by itself. */
    var ENDED = 'slingshot-agent-tail-ended';

    /**
     * Follows one operation and appends each event to a list, in the order they arrive.
     *
     * The stream is closed when the document goes away. A tail nobody is watching is a connection
     * the instance is holding open for nobody, and an author instance has a bounded number of them.
     */
    function follow(operation, into) {
        var source = new EventSource(EVENTS + '?operation=' + encodeURIComponent(operation));
        source.onmessage = function (event) {
            var line = document.createElement('li');
            line.textContent = event.data;
            into.appendChild(line);
        };
        source.onerror = function () {
            into.classList.add(ENDED);
            source.close();
        };
        window.addEventListener('unload', function () {
            source.close();
        });
        return source;
    }

    document.addEventListener('DOMContentLoaded', function () {
        var tail = document.querySelector('[data-slingshot-agent-operation]');
        if (tail) {
            follow(tail.getAttribute('data-slingshot-agent-operation'), tail);
        }
    });
}());
