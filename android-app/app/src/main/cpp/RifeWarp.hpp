// GPU-only custom ncnn layer: "rife.Warp".
//
// RIFE's exported graph contains custom rife.Warp nodes. This implementation
// provides a Vulkan compute path so ncnn never has to read the tensor back to
// the CPU for warping. The CPU forward overload intentionally returns an error:
// there is no CPU fallback.
#ifndef LSFG_ANDROID_RIFE_WARP_HPP
#define LSFG_ANDROID_RIFE_WARP_HPP

#include <layer.h>

namespace lsfg_android {

class RifeWarp final : public ncnn::Layer {
public:
    RifeWarp();

    int create_pipeline(const ncnn::Option& opt) override;
    int destroy_pipeline(const ncnn::Option& opt) override;

    int forward(const std::vector<ncnn::Mat>&,
                std::vector<ncnn::Mat>&,
                const ncnn::Option&) const override;

#if NCNN_VULKAN
    int forward(const std::vector<ncnn::VkMat>& bottom_blobs,
                std::vector<ncnn::VkMat>& top_blobs,
                ncnn::VkCompute& cmd,
                const ncnn::Option& opt) const override;

private:
    ncnn::Pipeline* pipeline_warp = nullptr;
#endif
};

} // namespace lsfg_android

#endif // LSFG_ANDROID_RIFE_WARP_HPP
