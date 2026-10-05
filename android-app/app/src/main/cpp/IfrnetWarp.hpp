// GPU-only custom ncnn layer: "ifrnet.Warp".
//
// IFRNet's graph uses the opposite bottom order from RIFE:
//   bottom[0] = flow, bottom[1] = image.
// The Vulkan implementation keeps that ordering and performs the complete
// bilinear warp on the GPU. CPU forward is deliberately disabled.
#ifndef LSFG_ANDROID_IFRNET_WARP_HPP
#define LSFG_ANDROID_IFRNET_WARP_HPP

#include <layer.h>

namespace lsfg_android {

class IfrnetWarp final : public ncnn::Layer {
public:
    IfrnetWarp();

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

#endif // LSFG_ANDROID_IFRNET_WARP_HPP
