#pragma once

#include "core/image.hpp"
#include "core/semaphore.hpp"
#include "core/fence.hpp"
#include "core/commandbuffer.hpp"
#include "shaders/alpha.hpp"
#include "shaders/beta.hpp"
#include "shaders/delta.hpp"
#include "shaders/gamma.hpp"
#include "shaders/generate.hpp"
#include "shaders/mipmaps.hpp"
#include "common/utils.hpp"

#include <vulkan/vulkan_core.h>

#include <vector>
#include <cstdint>
#include <array>

#ifdef __ANDROID__
struct AHardwareBuffer;
#endif

namespace LSFG_3_1P {

    using namespace LSFG;

    class Context {
    public:
        ///
        /// Create a context
        ///
        /// @param vk The Vulkan instance to use.
        /// @param in0 File descriptor for the first input image.
        /// @param in1 File descriptor for the second input image.
        /// @param outN File descriptors for the output images.
        /// @param extent The size of the images.
        /// @param format The format of the images.
        ///
        /// @throws LSFG::vulkan_error if the context fails to initialize.
        ///
        Context(Vulkan& vk,
            int in0, int in1, const std::vector<int>& outN,
            VkExtent2D extent, VkFormat format);

#ifdef __ANDROID__
        Context(Vulkan& vk,
            AHardwareBuffer* in0, AHardwareBuffer* in1,
            const std::vector<AHardwareBuffer*>& outN,
            VkExtent2D extent, VkFormat format);
#endif

        ///
        /// Present on the context.
        ///
        /// @param inSem Semaphore to wait on before starting the generation.
        /// @param outSem Semaphores to signal after each generation is done.
        ///
        /// @throws LSFG::vulkan_error if the context fails to present.
        ///
        void present(Vulkan& vk,
            int inSem, const std::vector<int>& outSem);
#ifdef __ANDROID__
        // Present using the current/previous capture AHBs directly. The AHBs are
        // imported into this context and bound to a safe 8-frame descriptor slot;
        // no pixel copy is performed.
        void presentAHB(Vulkan& vk, AHardwareBuffer* current, AHardwareBuffer* previous,
            int inSem, const std::vector<int>& outSem);
#endif

        ///
        /// Wait for the GPU work submitted by the most recent present() call
        /// to complete, without draining the rest of the VkDevice.
        ///
        /// This waits only on the completion fences of the ring slot used by
        /// the last present() — the same fences present() itself already
        /// waits on 8 frames later to recycle that slot. Callers that need
        /// to know "is the last present() done" (e.g. to safely read the
        /// output images from another VkDevice) should use this instead of
        /// vkDeviceWaitIdle(), which additionally drains unrelated queues
        /// and other contexts on the same device.
        ///
        /// No-op if present() has never been called.
        ///
        /// @param vk The Vulkan instance to use.
        ///
        /// @throws LSFG::vulkan_error if waiting fails.
        ///
        void waitForCompletion(Vulkan& vk) const;

        // Trivially copyable, moveable and destructible
        Context(const Context&) = default;
        Context& operator=(const Context&) = default;
        Context(Context&&) = default;
        Context& operator=(Context&&) = default;
        ~Context() = default;
    private:
        Core::Image inImg_0, inImg_1; // inImg_0 is next when fc % 2 == 0
        uint64_t frameIdx{0};

        struct RenderData {
            Core::Semaphore inSemaphore; // signaled when input is ready
            std::vector<Core::Semaphore> internalSemaphores; // signaled when first step is done
            std::vector<Core::Semaphore> outSemaphores; // signaled when each pass is done
            std::vector<Core::Fence> completionFences; // fence for completion of each pass

            Core::CommandBuffer cmdBuffer1;
            std::vector<Core::CommandBuffer> cmdBuffers2; // command buffers for second step

            bool shouldWait{false};
        };
        std::array<RenderData, 8> data;

        Shaders::Mipmaps mipmaps;
        std::array<Shaders::Alpha, 7> alpha;
        Shaders::Beta beta;
        std::array<Shaders::Gamma, 7> gamma;
        std::array<Shaders::Delta, 3> delta;
        Shaders::Generate generate;
    };

}
