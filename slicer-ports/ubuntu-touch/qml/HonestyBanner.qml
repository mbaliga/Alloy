// SPDX-License-Identifier: AGPL-3.0-only
// Shown on every screen that could be mistaken for slicing output.
import QtQuick 2.12
import QtQuick.Controls 2.12

Pane {
    objectName: "honestyBanner"
    padding: 8
    background: Rectangle { color: parent.palette.alternateBase; border.color: parent.palette.mid }
    contentItem: Label {
        wrapMode: Text.WordWrap
        font.pixelSize: 12
        text: backend.notice
        Accessible.name: qsTr("Notice: preparation and inspection only")
    }
}
