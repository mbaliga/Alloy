// SPDX-License-Identifier: AGPL-3.0-only
import QtQuick 2.12
import QtQuick.Controls 2.12
import QtQuick.Layouts 1.12

Page {
    ColumnLayout {
        anchors.fill: parent
        spacing: 8
        HonestyBanner { Layout.fillWidth: true }
        FilePicker {
            Layout.fillWidth: true
            Layout.leftMargin: 8; Layout.rightMargin: 8
            pickerId: "gcode"
            buttonText: qsTr("Open G-code")
            pathHint: "/home/phablet/Documents/part.gcode"
            onPicked: if (urls.length > 0) backend.inspectGcode(urls[0])
        }
        BusyIndicator { running: backend.busy; visible: backend.busy; Layout.alignment: Qt.AlignHCenter }
        Label {
            objectName: "gcodeVerdict"
            visible: backend.gcodeChecked
            Layout.fillWidth: true
            Layout.leftMargin: 8; Layout.rightMargin: 8
            wrapMode: Text.WordWrap
            font.bold: true
            font.pixelSize: 16
            // Verdict is a word and a glyph, never colour alone.
            text: (backend.gcodeValid ? "✓ " + qsTr("Passed safety preflight") : "✗ " + qsTr("Failed safety preflight"))
                  + "\n" + backend.gcodeName
        }
        ListView {
            objectName: "gcodeErrors"
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.leftMargin: 8; Layout.rightMargin: 8
            clip: true
            model: backend.gcodeErrors
            delegate: Label { width: ListView.view.width; wrapMode: Text.WordWrap; font.pixelSize: 12; text: "• " + modelData }
        }
        Label {
            Layout.fillWidth: true
            Layout.leftMargin: 8; Layout.rightMargin: 8
            wrapMode: Text.WordWrap
            font.pixelSize: 11
            text: qsTr("Checks the A1 Mini 180 mm volume: homing, heaters off, every move and arc inside the plate. A pass is not a print qualification.")
        }
    }
}
