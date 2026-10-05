#include "ncnn_cpu_policy.hpp"

#include <algorithm>
#include <cpu.h>

namespace lsfg_android {

void ncnnCpuSetAllCores() {
    // CPU-only inference: disable ncnn Vulkan compute and let the OS/ncnn
    // scheduler use the complete online CPU topology. No affinity mask or
    // big/LITTLE core selection is applied here.
    ncnn::set_cpu_powersave(0);
    ncnn::set_omp_num_threads(std::max(1, ncnn::get_cpu_count()));
}

int ncnnCpuThreadCount() {
    return std::max(1, ncnn::get_cpu_count());
}

} // namespace lsfg_android
