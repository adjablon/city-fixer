// Read-only location map for a single report.
// Vendored map library: Leaflet 1.9.4 (/js/leaflet.js, /css/leaflet.css).
(function () {
    'use strict';

    var DETAIL_ZOOM = 16;

    var mapElement = document.getElementById('detail-map');
    if (!mapElement) {
        return;
    }

    var latitude = parseFloat(mapElement.dataset.lat);
    var longitude = parseFloat(mapElement.dataset.lng);

    var map = L.map(mapElement, {
        dragging: false,
        touchZoom: false,
        doubleClickZoom: false,
        scrollWheelZoom: false,
        boxZoom: false,
        keyboard: false,
        zoomControl: false
    }).setView([latitude, longitude], DETAIL_ZOOM);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(map);

    L.marker([latitude, longitude]).addTo(map);
})();
