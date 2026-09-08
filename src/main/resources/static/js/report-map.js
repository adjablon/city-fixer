// Pin placement for the report submission form.
// Vendored map library: Leaflet 1.9.4 (/js/leaflet.js, /css/leaflet.css).
(function () {
    'use strict';

    var MAX_PHOTO_BYTES = 2 * 1024 * 1024;
    var LOCATED_ZOOM = 17;

    var mapElement = document.getElementById('map');
    if (!mapElement) {
        return;
    }

    var latitudeInput = document.getElementById('latitude');
    var longitudeInput = document.getElementById('longitude');
    var submitButton = document.getElementById('submit-report');
    var locateButton = document.getElementById('locate');
    var photoInput = document.getElementById('photo');
    var mapMessage = document.getElementById('map-message');
    var photoMessage = document.getElementById('photo-message');

    var defaultLatitude = parseFloat(mapElement.dataset.defaultLat);
    var defaultLongitude = parseFloat(mapElement.dataset.defaultLng);
    var defaultZoom = parseInt(mapElement.dataset.defaultZoom, 10);

    var map = L.map(mapElement).setView([defaultLatitude, defaultLongitude], defaultZoom);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(map);

    var marker = null;

    function writeCoordinates(latitude, longitude) {
        latitudeInput.value = latitude.toFixed(6);
        longitudeInput.value = longitude.toFixed(6);
        updateSubmitState();
    }

    function setPin(latitude, longitude) {
        if (marker === null) {
            marker = L.marker([latitude, longitude], { draggable: true }).addTo(map);
            marker.on('dragend', function () {
                var position = marker.getLatLng();
                writeCoordinates(position.lat, position.lng);
            });
        } else {
            marker.setLatLng([latitude, longitude]);
        }
        writeCoordinates(latitude, longitude);
    }

    function photoTooLarge() {
        return photoInput.files.length > 0 && photoInput.files[0].size > MAX_PHOTO_BYTES;
    }

    function updateSubmitState() {
        var hasPin = latitudeInput.value !== '' && longitudeInput.value !== '';
        submitButton.disabled = !hasPin || photoTooLarge();
    }

    map.on('click', function (event) {
        setPin(event.latlng.lat, event.latlng.lng);
    });

    locateButton.addEventListener('click', function () {
        if (!navigator.geolocation) {
            mapMessage.textContent = 'This browser cannot look up your location. Tap the map to place a pin.';
            return;
        }
        mapMessage.textContent = 'Locating…';
        navigator.geolocation.getCurrentPosition(
            function (position) {
                mapMessage.textContent = '';
                setPin(position.coords.latitude, position.coords.longitude);
                map.setView([position.coords.latitude, position.coords.longitude], LOCATED_ZOOM);
            },
            function (error) {
                mapMessage.textContent = error.code === error.PERMISSION_DENIED
                    ? 'Location permission denied. Tap the map to place a pin instead.'
                    : 'Could not determine your location. Tap the map to place a pin instead.';
            },
            { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 }
        );
    });

    photoInput.addEventListener('change', function () {
        // Convenience only — PhotoValidator on the server is the authoritative check.
        photoMessage.textContent = photoTooLarge()
            ? 'Photo must be 2 MB or smaller. Please choose a smaller file.'
            : '';
        updateSubmitState();
    });

    // A rejected submit re-renders the page with the submitted coordinates, so restore
    // the pin rather than making the user place it again.
    if (latitudeInput.value !== '' && longitudeInput.value !== '') {
        var restoredLatitude = parseFloat(latitudeInput.value);
        var restoredLongitude = parseFloat(longitudeInput.value);
        setPin(restoredLatitude, restoredLongitude);
        map.setView([restoredLatitude, restoredLongitude], defaultZoom);
    }

    updateSubmitState();
})();
