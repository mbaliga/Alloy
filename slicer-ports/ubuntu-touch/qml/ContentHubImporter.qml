// SPDX-License-Identifier: AGPL-3.0-only
// Ubuntu Touch Content Hub import. EVIDENCE CLASS: CODE-ONLY. The Lomiri.Content module does not exist in the
// build container, so this file has never been loaded; the API usage follows the Content Hub documentation for
// Lomiri.Content 1.1 and must be verified on a device (slicer-ports/ubuntu-touch/OWNER_VERIFY.md).
import QtQuick 2.12
import QtQuick.Controls 2.12
import Lomiri.Content 1.1

Item {
    id: hub
    signal accepted(var urls)
    property var activeTransfer: null

    function open() { picker.open() }

    Popup {
        id: picker
        parent: Overlay.overlay
        x: 0; y: 0
        width: parent ? parent.width : 360
        height: parent ? parent.height : 640
        modal: true
        padding: 0
        ContentPeerPicker {
            anchors.fill: parent
            showTitle: true
            contentType: ContentType.All
            handler: ContentHandler.Source
            onPeerSelected: {
                peer.selectionType = ContentTransfer.Multiple
                hub.activeTransfer = peer.request()
                picker.close()
            }
            onCancelPressed: picker.close()
        }
    }

    Connections {
        target: hub.activeTransfer
        ignoreUnknownSignals: true
        onStateChanged: {
            if (hub.activeTransfer && hub.activeTransfer.state === ContentTransfer.Charged) {
                var urls = []
                for (var i = 0; i < hub.activeTransfer.items.length; ++i)
                    urls.push(hub.activeTransfer.items[i].url)
                hub.accepted(urls)
                hub.activeTransfer.finalize()
            }
        }
    }
}
