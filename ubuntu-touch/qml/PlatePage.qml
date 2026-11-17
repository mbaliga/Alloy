// SPDX-License-Identifier: AGPL-3.0-only
import QtQuick 2.12
import QtQuick.Controls 2.12
import QtQuick.Layouts 1.12

Page {
    id: page
    ColumnLayout {
        anchors.fill: parent
        spacing: 8
        HonestyBanner { Layout.fillWidth: true }
        Label {
            objectName: "plateError"
            visible: backend.plateError.length > 0
            Layout.fillWidth: true
            Layout.leftMargin: 8; Layout.rightMargin: 8
            wrapMode: Text.WordWrap
            text: backend.plateError
        }
        Canvas {
            id: canvas
            objectName: "plateCanvas"
            Layout.fillWidth: true
            Layout.preferredHeight: width
            Layout.margins: 8
            Accessible.name: qsTr("Plate preview: %1 part(s) on the build plate").arg(backend.placements.length)
            property var parts: backend.placements
            onPartsChanged: requestPaint()
            onWidthChanged: requestPaint()
            onPaint: {
                var ctx = getContext("2d")
                ctx.reset()
                var scale = Math.min(width / backend.bedX, height / backend.bedY)
                var frameW = backend.bedX * scale, frameH = backend.bedY * scale
                ctx.lineWidth = 2
                ctx.strokeStyle = String(page.palette.windowText)
                ctx.fillStyle = String(page.palette.windowText)
                ctx.setLineDash([])
                ctx.strokeRect(1, 1, frameW - 2, frameH - 2)
                ctx.font = "14px sans-serif"
                ctx.textAlign = "center"
                ctx.textBaseline = "middle"
                for (var i = 0; i < parts.length; ++i) {
                    var p = parts[i]
                    var rx = p.x * scale, ry = frameH - (p.y + p.depth) * scale
                    var rw = p.width * scale, rh = p.depth * scale
                    // Colour never carries meaning alone: rotated parts get a dashed stroke AND a "(90°)" label.
                    ctx.strokeStyle = String(page.palette.highlight)
                    ctx.setLineDash(p.rotated ? [6, 3] : [])
                    ctx.strokeRect(rx, ry, rw, rh)
                    ctx.fillStyle = String(page.palette.windowText)
                    ctx.fillText(p.label, rx + rw / 2, ry + rh / 2)
                }
            }
        }
        Label {
            Layout.fillWidth: true
            Layout.leftMargin: 8; Layout.rightMargin: 8
            wrapMode: Text.WordWrap
            font.pixelSize: 12
            text: qsTr("Packing is a footprint check. It never tilts or stands a part up. Dashed outline and (90°) mean the part is turned 90° on the plate.")
        }
        Repeater {
            model: backend.placements
            Label {
                Layout.leftMargin: 8
                text: modelData.label + ": " + modelData.name
                font.pixelSize: 12
            }
        }
        Item { Layout.fillHeight: true }
    }
}
