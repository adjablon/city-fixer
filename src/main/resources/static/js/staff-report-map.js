// All-reports map for staff triage: one coloured marker per report, popup links to detail.
// Vendored map library: Leaflet 1.9.4 (/js/leaflet.js, /css/leaflet.css).
(function () {
    'use strict';

    // Source of truth is the status badge palette in report-detail.html / report-list.html;
    // these are the badge text colours, which stay legible as fills over map tiles.
    var STATUS_COLOURS = {
        NEW: '#1d4ed8',
        IN_PROGRESS: '#b45309',
        RESOLVED: '#15803d',
        REJECTED: '#b91c1c'
    };
    var FALLBACK_COLOUR = '#475569';
    var MARKER_RADIUS = 8;

    var mapElement = document.getElementById('staff-map');
    if (!mapElement) {
        return;
    }

    var defaultLatitude = parseFloat(mapElement.dataset.defaultLat);
    var defaultLongitude = parseFloat(mapElement.dataset.defaultLng);
    var defaultZoom = parseInt(mapElement.dataset.defaultZoom, 10);

    var pins = [];
    try {
        pins = JSON.parse(mapElement.dataset.reports || '[]');
    } catch (e) {
        // Malformed payload must not leave a blank page: fall back to an empty map so the
        // legend and navigation still work.
        window.console && console.error('Could not parse report pins', e);
    }

    var map = L.map(mapElement).setView([defaultLatitude, defaultLongitude], defaultZoom);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(map);

    // Built as DOM nodes with textContent, never innerHTML: popup content is server data.
    function popupContent(pin) {
        var container = document.createElement('div');

        var category = document.createElement('div');
        category.className = 'popup-category';
        category.textContent = pin.categoryLabel;
        container.appendChild(category);

        var meta = document.createElement('div');
        meta.className = 'popup-meta';
        meta.textContent = pin.statusLabel + ' · ' + pin.createdAt;
        container.appendChild(meta);

        var link = document.createElement('a');
        link.href = '/staff/reports/' + encodeURIComponent(pin.id);
        link.textContent = 'Open report';
        container.appendChild(link);

        return container;
    }

    var markers = [];
    pins.forEach(function (pin) {
        var marker = L.circleMarker([pin.latitude, pin.longitude], {
            radius: MARKER_RADIUS,
            color: '#ffffff',
            weight: 2,
            fillColor: STATUS_COLOURS[pin.status] || FALLBACK_COLOUR,
            fillOpacity: 1
        }).addTo(map);
        marker.bindPopup(popupContent(pin));
        markers.push(marker);
    });

    if (markers.length > 0) {
        map.fitBounds(L.featureGroup(markers).getBounds(), { padding: [32, 32], maxZoom: 16 });
    }
})();
