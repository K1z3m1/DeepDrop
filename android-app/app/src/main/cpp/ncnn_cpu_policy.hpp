#pragma once

namespace lsfg_android {

// CPU-only ncnn policy. All online CPUs are available; no CPU affinity
// or core-class pinning is applied, so Android's scheduler controls placement.
// This is deliberately not LITTLE-only: CPU work is used to keep Vulkan/GPU
// compute reserved for the dedicated LSFG frame-generation pipeline.
void ncnnCpuSetAllCores();
int ncnnCpuThreadCount();

} // namespace lsfg_android
