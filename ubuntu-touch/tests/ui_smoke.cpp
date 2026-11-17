// SPDX-License-Identifier: AGPL-3.0-only
// Headless UI smoke test: loads the real Main.qml (offscreen platform, software scene graph), drives the
// real widgets with synthetic mouse clicks, checks backend state and that each tab actually renders pixels.
// This proves the QML parses/binds and the C++ adapter works under Qt 5.15. It proves NOTHING about Lomiri,
// the Content Hub, AppArmor, touch input or a real GPU: those are NEEDS-DEVICE-VALIDATION.
#include <QDir>
#include <QFile>
#include <QGuiApplication>
#include <QImage>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickItem>
#include <QQuickItemGrabResult>
#include <QSharedPointer>
#include <QQuickWindow>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QtTest>

#include "alloybackend.h"

class UiSmoke : public QObject {
    Q_OBJECT
    AlloyBackend backend_;
    QQmlApplicationEngine engine_;
    QQuickWindow* window_ = nullptr;
    QTemporaryDir tmp_;
    QString outDir_;

    static QQuickItem* walk(QQuickItem* root, const QString& name) {
        if (root->objectName() == name) return root;
        for (QQuickItem* c : root->childItems())
            if (QQuickItem* r = walk(c, name)) return r;
        return nullptr;
    }
    QQuickItem* find(const QString& name) {
        auto* item = walk(window_->contentItem(), name);
        if (!item) qFatal("missing QML object %s", qPrintable(name));
        return item;
    }
    QQuickItem* findOrNull(const QString& name) { return walk(window_->contentItem(), name); }
    void click(QQuickItem* item) {
        QVERIFY(item->isVisible());
        QPointF c = item->mapToScene(QPointF(item->width() / 2, item->height() / 2));
        QTest::mouseClick(window_, Qt::LeftButton, Qt::NoModifier, c.toPoint());
    }
    void snapshot(const QString& name, bool requireContent = true) {
        QTest::qWait(150);
        QSharedPointer<QQuickItemGrabResult> result = window_->contentItem()->grabToImage();
        if (!result) { QFAIL("grabToImage returned no result"); }
        QSignalSpy ready(result.data(), &QQuickItemGrabResult::ready);
        QVERIFY(ready.wait(3000));
        QImage img = result->image();
        QVERIFY(!img.isNull());
        QSet<QRgb> colours;
        for (int y = 0; y < img.height(); y += 3)
            for (int x = 0; x < img.width(); x += 3) colours.insert(img.pixel(x, y));
        if (requireContent) QVERIFY2(colours.size() > 4, qPrintable(name + ": rendered image looks blank"));
        if (!outDir_.isEmpty()) img.save(QDir(outDir_).filePath(name + ".png"));
    }
    QString write(const QString& name, const QByteArray& data) {
        QString p = tmp_.filePath(name);
        QFile f(p);
        if (!f.open(QIODevice::WriteOnly)) qFatal("cannot write %s", qPrintable(p));
        f.write(data);
        return p;
    }
    static QByteArray asciiTri(float s) {
        return QByteArray("solid c\nvertex 0 0 0\nvertex ") + QByteArray::number(s) + " 0 0\nvertex 0 " + QByteArray::number(s) + " " +
               QByteArray::number(s) + "\nendsolid\n";
    }
    void setText(QQuickItem* field, const QString& text) {
        QVERIFY(field->setProperty("text", text));
        QTest::qWait(60);  // let bindings (button enabled state) and layout settle before a synthetic click
    }

private slots:
    void initTestCase() {
        QVERIFY(tmp_.isValid());
        outDir_ = qEnvironmentVariable("ALLOY_SMOKE_OUT");
        if (!outDir_.isEmpty()) QDir().mkpath(outDir_);
        engine_.rootContext()->setContextProperty("backend", &backend_);
        engine_.load(QUrl("qrc:/qml/Main.qml"));
        QVERIFY(!engine_.rootObjects().isEmpty());
        window_ = qobject_cast<QQuickWindow*>(engine_.rootObjects().first());
        QVERIFY(window_);
        window_->show();
        QVERIFY(QTest::qWaitForWindowExposed(window_));
    }

    void honestyBannerOnEveryActionTab() {
        QVERIFY(findOrNull("honestyBanner"));
        QVERIFY(backend_.notice().contains("does not slice"));
        snapshot("1-models-empty");
    }

    void contentHubFallsBackToPathEntryWhereModuleMissing() {
        // Lomiri.Content does not exist in this environment, so the dynamic Loader must fail soft.
        QVERIFY(find("modelPath")->isVisible());
        QVERIFY(findOrNull("modelChoose") == nullptr || !findOrNull("modelChoose")->isVisible());
    }

    void importTwoStlsThroughTheUi() {
        QString a = write("a.stl", asciiTri(40));
        QString b = write("b.STL", asciiTri(60));
        for (const QString& path : {a, b}) {
            setText(find("modelPath"), path);
            click(find("modelButton"));
        }
        QCOMPARE(backend_.models().size(), 2);
        QVERIFY(backend_.plateError().isEmpty());
        QCOMPARE(backend_.placements().size(), 2);
        QCOMPARE(find("modelList")->property("count").toInt(), 2);
        snapshot("2-models-two");
    }

    void rejectsNonStlWithAVisibleError() {
        setText(find("modelPath"), write("x.3mf", "PK"));
        click(find("modelButton"));
        QVERIFY(backend_.importError().contains("only STL"));
        QVERIFY(find("importError")->isVisible());
        QCOMPARE(backend_.models().size(), 2);
        backend_.clearImportError();
    }

    void plateTabRendersAndTallPartIsReported() {
        find("tabs")->setProperty("currentIndex", 1);
        snapshot("3-plate");
        // 200 mm tall part exceeds the 180 mm build height.
        backend_.importModels(write("tall.stl", asciiTri(200)));
        QVERIFY2(backend_.plateError().contains("taller"), qPrintable(backend_.plateError()));
        QVERIFY(find("plateError")->isVisible());
        snapshot("4-plate-error");
        backend_.removeModel(backend_.models().last().toMap()["id"].toInt());
        QVERIFY(backend_.plateError().isEmpty());
    }

    void gcodeCheckPassesAndFailsThroughTheUi() {
        find("tabs")->setProperty("currentIndex", 2);
        QSignalSpy done(&backend_, &AlloyBackend::gcodeFinished);
        setText(find("gcodePath"), write("good.gcode", "G28\nG90\nM83\nG92 E0\nG1 X10 Y10 E0.4\nM104 S0\nM140 S0\n"));
        click(find("gcodeButton"));
        QTRY_VERIFY_WITH_TIMEOUT(done.count() >= 1, 5000);  // signal may fire inside the click itself
        done.clear();
        QVERIFY(backend_.gcodeChecked() && backend_.gcodeValid());
        QCOMPARE(backend_.gcodeName(), QString("good.gcode"));
        snapshot("5-gcode-pass");

        setText(find("gcodePath"), write("bad.gcode", "G28\nG90\nM83\nG1 X180.6 Y10 E0.4\nM104 S0\nM140 S0\n"));
        click(find("gcodeButton"));
        QTRY_VERIFY_WITH_TIMEOUT(done.count() >= 1, 5000);  // signal may fire inside the click itself
        done.clear();
        QVERIFY(!backend_.gcodeValid());
        QVERIFY2(backend_.gcodeErrors().join('|').contains("build plate"), qPrintable(backend_.gcodeErrors().join('|')));
        snapshot("6-gcode-fail");
    }

    void missingFileIsAReportedErrorNotACrash() {
        QSignalSpy done(&backend_, &AlloyBackend::gcodeFinished);
        backend_.inspectGcode("/nonexistent/none.gcode");
        QTRY_VERIFY_WITH_TIMEOUT(done.count() >= 1, 5000);  // signal may fire inside the click itself
        done.clear();
        QVERIFY(backend_.importError().contains("not found"));
    }

    void aboutStatesNoNetworkAndNotAPort() {
        find("tabs")->setProperty("currentIndex", 3);
        QVERIFY(backend_.privacyText().contains("no networking"));
        QVERIFY(backend_.aboutText().contains("not a port"));
        snapshot("7-about");
    }
};

int main(int argc, char** argv) {
    qputenv("QT_QPA_PLATFORM", qgetenv("QT_QPA_PLATFORM").isEmpty() ? QByteArray("offscreen") : qgetenv("QT_QPA_PLATFORM"));
    QQuickWindow::setSceneGraphBackend(QSGRendererInterface::Software);
    QGuiApplication app(argc, argv);
    UiSmoke t;
    return QTest::qExec(&t, argc, argv);
}

#include "ui_smoke.moc"
