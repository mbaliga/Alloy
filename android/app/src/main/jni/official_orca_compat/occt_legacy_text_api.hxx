#pragma once

// The pinned Orca source uses two OCCT 7.x names that were removed or renamed
// in Alloy's pinned OCCT 8.x build. Keep the compatibility surface limited to
// that upstream translation unit; do not edit the Orca submodule or alter the
// behavior of OCCT's UTF-8 iterator / font-name sequence.
#include <NCollection_Sequence.hxx>
#include <NCollection_UtfIterator.hxx>
#include <TCollection_HAsciiString.hxx>

using TColStd_SequenceOfHAsciiString =
    NCollection_Sequence<opencascade::handle<TCollection_HAsciiString>>;
using NCollection_Utf8Iter = NCollection_UtfIterator<char>;
