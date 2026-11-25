// SPDX-License-Identifier: AGPL-3.0-only
#include "alloybackend.h"

#include <QFile>
#include <QFileInfo>
#include <QJSValue>
#include <QVariantMap>
#include <QtConcurrent>

using alloy::GcodeStream;

AlloyBackend::AlloyBackend(QObject* parent) : QObject(parent) {
    connect(&watcher_, &QFutureWatcher<GcodeOutcome>::finished, this, &AlloyBackend::onGcodeDone);
    refreshReview();
}

QString AlloyBackend::notice() const {
    return tr("Preparation and inspection only. This build does not slice and its output is not print-qualified.");
}

QString AlloyBackend::privacyText() const {
    return tr("No network access, no telemetry, no analytics, no accounts. The app's AppArmor policy requests no "
              "networking permission, so the system would block a connection even if the code tried one. Files you "
              "pick are read once and never leave the device.");
}

QString AlloyBackend::aboutText() const {
    return tr("A companion for preparing and inspecting 3D-print files on Ubuntu Touch. It is not a port of the "
              "Android slicer: the slicing engine (libslic3r/Orca through JNI) exists only for Android and has no "
              "verified Linux arm64 build, so this app never slices. G-code safety rules and plate arrangement are "
              "the same logic as the Android app, ported to C++ and checked against the Java classes on a test corpus. "
              "Licence: GNU AGPL-3.0. Source: github.com/mbaliga/Alloy");
}

QVariantList AlloyBackend::models() const {
    QVariantList out;
    for (const auto& m : session_.models()) {
        QVariantMap row;
        row["id"] = m.id;
        row["name"] = QString::fromStdString(m.name);
        row["width"] = m.bounds.width();
        row["depth"] = m.bounds.depth();
        row["height"] = m.bounds.height();
        row["triangles"] = static_cast<qulonglong>(m.mesh.triangleCount());
        out.push_back(row);
    }
    return out;
}

QVariantList AlloyBackend::placements() const {
    QVariantList out;
    const auto& ms = session_.models();
    for (const auto& p : review_.placements) {
        QVariantMap row;
        row["index"] = p.sourceIndex;
        row["label"] = QString::number(p.sourceIndex + 1) + (p.rotationDegrees == 90.0f ? QString::fromUtf8(" (90\xC2\xB0)") : QString());
        row["name"] = p.sourceIndex < static_cast<int>(ms.size()) ? QString::fromStdString(ms[p.sourceIndex].name) : QString();
        row["x"] = p.x;
        row["y"] = p.y;
        row["width"] = p.width;
        row["depth"] = p.depth;
        row["rotated"] = p.rotationDegrees == 90.0f;
        out.push_back(row);
    }
    return out;
}

// QML hands JS arrays/strings over as QJSValue wrapped in a QVariant; unwrap to plain QVariant types.
static QVariant unwrap(const QVariant& v) {
    return v.userType() == qMetaTypeId<QJSValue>() ? v.value<QJSValue>().toVariant() : v;
}

QString AlloyBackend::toLocalPath(const QVariant& in) {
    const QVariant v = unwrap(in);
    if (v.canConvert<QUrl>() && v.type() == QVariant::Url) {
        QUrl u = v.toUrl();
        return u.isLocalFile() ? u.toLocalFile() : u.toString();
    }
    QString s = v.toString();
    QUrl u(s);
    if (u.isLocalFile()) return u.toLocalFile();
    return s;
}

void AlloyBackend::setImportError(const QString& e) {
    if (e == importError_) return;
    importError_ = e;
    emit importErrorChanged();
}

void AlloyBackend::clearImportError() { setImportError(QString()); }

void AlloyBackend::refreshReview() {
    review_ = session_.review();
    plateError_ = QString::fromStdString(review_.error);
    emit modelsChanged();
}

void AlloyBackend::importModels(const QVariant& urls) {
    const QVariant plain = unwrap(urls);
    QVariantList list = plain.type() == QVariant::List ? plain.toList() : QVariantList{plain};
    QStringList errors;
    for (const QVariant& v : list) {
        QString path = toLocalPath(v);
        QFileInfo info(path);
        QString name = info.fileName();
        if (!info.isFile()) { errors << name + ": file not found or not readable"; continue; }
        if (info.size() > kMaxModelBytes) { errors << name + ": larger than the 200 MB limit"; continue; }
        QFile f(path);
        if (!f.open(QIODevice::ReadOnly)) { errors << name + ": " + f.errorString(); continue; }
        QByteArray bytes = f.readAll();
        std::vector<std::uint8_t> data(bytes.begin(), bytes.end());
        auto r = session_.importModel(name.toStdString(), data);
        if (!r.ok) errors << QString::fromStdString(r.error);
    }
    refreshReview();
    setImportError(errors.join('\n'));
}

void AlloyBackend::removeModel(int id) {
    session_.remove(id);
    refreshReview();
}

AlloyBackend::GcodeOutcome AlloyBackend::scanFile(const QString& path, alloy::BuildVolume volume) {
    GcodeOutcome out;
    QFileInfo info(path);
    out.name = info.fileName();
    if (!info.isFile()) { out.readError = out.name + ": file not found or not readable"; return out; }
    if (info.size() > kMaxGcodeBytes) { out.readError = out.name + ": larger than the 128 MB limit"; return out; }
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) { out.readError = out.name + ": " + f.errorString(); return out; }
    GcodeStream stream{std::optional<alloy::BuildVolume>(volume)};
    QByteArray chunk(1 << 20, Qt::Uninitialized);
    // Stream in 1 MiB chunks so a large file is never held in memory.
    while (true) {
        qint64 n = f.read(chunk.data(), chunk.size());
        if (n < 0) { out.readError = out.name + ": " + f.errorString(); return out; }
        if (n == 0) break;
        if (!stream.accept(chunk.constData(), static_cast<std::size_t>(n))) break;
    }
    // Note: a blank file yields the standard "missing G90 ..." errors rather than the in-memory "empty" message.
    out.report = stream.finish();
    return out;
}

void AlloyBackend::inspectGcode(const QVariant& url) {
    if (busy_) return;
    QString path = toLocalPath(url);
    busy_ = true;
    emit busyChanged();
    watcher_.setFuture(QtConcurrent::run(&AlloyBackend::scanFile, path, session_.volume));
}

void AlloyBackend::onGcodeDone() {
    GcodeOutcome o = watcher_.result();
    busy_ = false;
    if (!o.readError.isEmpty()) {
        gcodeChecked_ = false;
        setImportError(o.readError);
    } else {
        gcodeChecked_ = true;
        gcodeName_ = o.name;
        gcodeValid_ = o.report.isValid();
        gcodeErrors_.clear();
        for (const auto& e : o.report.errors) gcodeErrors_ << QString::fromStdString(e);
    }
    emit busyChanged();
    emit gcodeChanged();
    emit gcodeFinished();
}
