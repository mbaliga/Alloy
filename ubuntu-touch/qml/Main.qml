// SPDX-License-Identifier: AGPL-3.0-only
// Alloy for Ubuntu Touch: preparation and inspection companion (not a slicer).
// Plain QtQuick.Controls 2 on purpose: on Ubuntu Touch it picks up the system (Suru) style, and it lets this UI be
// loaded and rendered headless in CI/containers, which Lomiri.Components cannot be.
import QtQuick 2.12
import QtQuick.Controls 2.12
import QtQuick.Layouts 1.12

ApplicationWindow {
    id: window
    visible: true
    width: 360
    height: 720
    title: "Alloy"

    header: ToolBar {
        Label {
            anchors.centerIn: parent
            text: tabs.currentIndex === 0 ? qsTr("Models")
                : tabs.currentIndex === 1 ? qsTr("Plate (A1 Mini 180 mm)")
                : tabs.currentIndex === 2 ? qsTr("G-code check") : qsTr("About")
            font.bold: true
            Accessible.role: Accessible.Heading
        }
    }

    footer: TabBar {
        id: tabs
        objectName: "tabs"
        currentIndex: 0
        TabButton { text: qsTr("Models") }
        TabButton { text: qsTr("Plate") }
        TabButton { text: qsTr("G-code") }
        TabButton { text: qsTr("About") }
    }

    StackLayout {
        anchors.fill: parent
        currentIndex: tabs.currentIndex
        ModelsPage { }
        PlatePage { }
        GcodePage { }
        AboutPage { }
    }
}
