// SPDX-License-Identifier: AGPL-3.0-only
// Thin Qt adapter over the Qt-free alloycore. All decisions live in the core; this class reads files,
// converts types for QML and runs the G-code scan off the UI thread. It opens no sockets.
#pragma once
#include <QFutureWatcher>
#include <QObject>
#include <QStringList>
#include <QUrl>
#include <QVariantList>

#include "alloycore/preparation_session.hpp"

class AlloyBackend : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantList models READ models NOTIFY modelsChanged)
    Q_PROPERTY(QVariantList placements READ placements NOTIFY modelsChanged)
    Q_PROPERTY(QString plateError READ plateError NOTIFY modelsChanged)
    Q_PROPERTY(double bedX READ bedX CONSTANT)
    Q_PROPERTY(double bedY READ bedY CONSTANT)
    Q_PROPERTY(QString importError READ importError NOTIFY importErrorChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(bool gcodeChecked READ gcodeChecked NOTIFY gcodeChanged)
    Q_PROPERTY(bool gcodeValid READ gcodeValid NOTIFY gcodeChanged)
    Q_PROPERTY(QString gcodeName READ gcodeName NOTIFY gcodeChanged)
    Q_PROPERTY(QStringList gcodeErrors READ gcodeErrors NOTIFY gcodeChanged)
    Q_PROPERTY(QString notice READ notice CONSTANT)
    Q_PROPERTY(QString privacyText READ privacyText CONSTANT)
    Q_PROPERTY(QString aboutText READ aboutText CONSTANT)

public:
    explicit AlloyBackend(QObject* parent = nullptr);

    static constexpr qint64 kMaxModelBytes = 200LL * 1024 * 1024;
    static constexpr qint64 kMaxGcodeBytes = 128LL * 1024 * 1024;

    QVariantList models() const;
    QVariantList placements() const;
    QString plateError() const { return plateError_; }
    double bedX() const { return session_.volume.bedX; }
    double bedY() const { return session_.volume.bedY; }
    QString importError() const { return importError_; }
    bool busy() const { return busy_; }
    bool gcodeChecked() const { return gcodeChecked_; }
    bool gcodeValid() const { return gcodeValid_; }
    QString gcodeName() const { return gcodeName_; }
    QStringList gcodeErrors() const { return gcodeErrors_; }
    QString notice() const;
    QString privacyText() const;
    QString aboutText() const;

    /// Accepts file:// URLs, plain paths, or a QVariantList of either (Content Hub hands back several).
    Q_INVOKABLE void importModels(const QVariant& urls);
    Q_INVOKABLE void removeModel(int id);
    Q_INVOKABLE void inspectGcode(const QVariant& url);
    Q_INVOKABLE void clearImportError();

signals:
    void modelsChanged();
    void importErrorChanged();
    void busyChanged();
    void gcodeChanged();
    void gcodeFinished();

private:
    struct GcodeOutcome {
        QString name;
        QString readError;
        alloy::GcodeSafetyReport report;
    };
    static QString toLocalPath(const QVariant& v);
    static GcodeOutcome scanFile(const QString& path, alloy::BuildVolume volume);
    void setImportError(const QString& e);
    void refreshReview();
    void onGcodeDone();

    alloy::PreparationSession session_;
    alloy::PlateReview review_;
    QString plateError_;
    QString importError_;
    bool busy_ = false;
    bool gcodeChecked_ = false, gcodeValid_ = false;
    QString gcodeName_;
    QStringList gcodeErrors_;
    QFutureWatcher<GcodeOutcome> watcher_;
};
