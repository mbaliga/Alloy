// SPDX-License-Identifier: AGPL-3.0-only
// File selection. Uses the Ubuntu Touch Content Hub when its QML module is present (ContentHubImporter.qml,
// loaded dynamically so this UI still loads where the module does not exist), and always offers a path field,
// which is also how the headless smoke test drives the UI.
import QtQuick 2.12
import QtQuick.Controls 2.12
import QtQuick.Layouts 1.12

ColumnLayout {
    id: root
    property string pickerId: "file"
    property string buttonText: qsTr("Choose file")
    property string pathHint: "/home/phablet/Documents/part.stl"
    signal picked(var urls)

    Loader {
        id: hub
        objectName: "contentHubLoader"
        source: "ContentHubImporter.qml"
        asynchronous: false
        onLoaded: item.accepted.connect(function (urls) { root.picked(urls) })
    }
    readonly property bool hubAvailable: hub.status === Loader.Ready

    Button {
        objectName: root.pickerId + "Choose"
        visible: root.hubAvailable
        text: root.buttonText
        Layout.fillWidth: true
        onClicked: hub.item.open()
    }
    RowLayout {
        Layout.fillWidth: true
        visible: !root.hubAvailable
        TextField {
            id: pathField
            objectName: root.pickerId + "Path"
            Layout.fillWidth: true
            placeholderText: root.pathHint
            inputMethodHints: Qt.ImhNoPredictiveText | Qt.ImhUrlCharactersOnly
            onAccepted: addButton.clicked()
        }
        Button {
            id: addButton
            objectName: root.pickerId + "Button"
            text: root.buttonText
            enabled: pathField.text.length > 0
            onClicked: { root.picked([pathField.text]); pathField.clear() }
        }
    }
    Label {
        visible: !root.hubAvailable
        Layout.fillWidth: true
        wrapMode: Text.WordWrap
        font.pixelSize: 11
        opacity: 0.8
        text: qsTr("The system file picker (Content Hub) is not available in this environment. Type a file path instead.")
    }
}
