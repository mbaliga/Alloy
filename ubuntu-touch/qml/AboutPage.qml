// SPDX-License-Identifier: AGPL-3.0-only
import QtQuick 2.12
import QtQuick.Controls 2.12
import QtQuick.Layouts 1.12

Page {
    Flickable {
        anchors.fill: parent
        contentHeight: col.implicitHeight + 24
        clip: true
        ColumnLayout {
            id: col
            x: 12; y: 12
            width: parent.width - 24
            spacing: 12
            Label { text: qsTr("What this is"); font.bold: true }
            Label { Layout.fillWidth: true; wrapMode: Text.WordWrap; text: backend.aboutText }
            Label { text: qsTr("Privacy"); font.bold: true }
            Label { objectName: "privacyText"; Layout.fillWidth: true; wrapMode: Text.WordWrap; text: backend.privacyText }
        }
    }
}
