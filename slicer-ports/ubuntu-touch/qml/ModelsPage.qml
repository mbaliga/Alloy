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
            pickerId: "model"
            buttonText: qsTr("Import STL")
            onPicked: backend.importModels(urls)
        }
        Label {
            objectName: "importError"
            visible: backend.importError.length > 0
            Layout.fillWidth: true
            Layout.leftMargin: 8; Layout.rightMargin: 8
            wrapMode: Text.WordWrap
            // Colour never carries meaning alone: the text itself says it is an error.
            text: qsTr("Import problem: ") + backend.importError
            font.bold: true
            Accessible.role: Accessible.AlertMessage
        }
        Label {
            visible: backend.models.length === 0
            Layout.fillWidth: true
            Layout.leftMargin: 8; Layout.rightMargin: 8
            wrapMode: Text.WordWrap
            text: qsTr("No models yet. STL (binary or ASCII) is the only supported format in this build; OBJ, 3MF and STEP are Android-only.")
        }
        ListView {
            id: list
            objectName: "modelList"
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            model: backend.models
            delegate: ItemDelegate {
                width: list.width
                contentItem: RowLayout {
                    ColumnLayout {
                        Layout.fillWidth: true
                        Label { text: modelData.name; font.bold: true; elide: Text.ElideMiddle; Layout.fillWidth: true }
                        Label {
                            text: qsTr("%1 x %2 x %3 mm, %4 triangles")
                                .arg(modelData.width.toFixed(1)).arg(modelData.depth.toFixed(1))
                                .arg(modelData.height.toFixed(1)).arg(modelData.triangles)
                            font.pixelSize: 12
                        }
                    }
                    Button {
                        text: qsTr("Remove")
                        onClicked: backend.removeModel(modelData.id)
                        Accessible.name: qsTr("Remove %1").arg(modelData.name)
                    }
                }
            }
        }
    }
}
