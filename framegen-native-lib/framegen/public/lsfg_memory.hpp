#pragma once

#include "core/memorypool.hpp"

namespace LSFG {

    /// Sizes from the most recently created context (for logging / diagnostics).
    __attribute__((visibility("default")))
    Core::MemoryStats getMemoryStats();

}
