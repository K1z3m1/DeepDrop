#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "core/semaphore.hpp"
#include "core/device.hpp"
#include "common/exception.hpp"

#include <optional>
#include <cstdint>
#include <memory>
#include <stdexcept>

using namespace LSFG::Core;

VkExternalSemaphoreHandleTypeFlagBits Semaphore::externalFdHandleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;

void Semaphore::setExternalFdHandleType(VkExternalSemaphoreHandleTypeFlagBits type) noexcept {
    externalFdHandleType = type;
}

Semaphore::Semaphore(const Core::Device& device, std::optional<uint32_t> initial) {
    // create semaphore
    const VkSemaphoreTypeCreateInfo typeInfo{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO,
        .semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE,
        .initialValue = initial.value_or(0)
    };
    const VkSemaphoreCreateInfo desc{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
        .pNext = initial.has_value() ? &typeInfo : nullptr,
    };
    VkSemaphore semaphoreHandle{};
    auto res = vkCreateSemaphore(device.handle(), &desc, nullptr, &semaphoreHandle);
    if (res != VK_SUCCESS || semaphoreHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Unable to create semaphore");

    // store semaphore in shared ptr
    this->isTimeline = initial.has_value();
    this->semaphore = std::shared_ptr<VkSemaphore>(
        new VkSemaphore(semaphoreHandle),
        [dev = device.handle()](VkSemaphore* semaphoreHandle) {
            vkDestroySemaphore(dev, *semaphoreHandle, nullptr);
        }
    );
}

Semaphore::Semaphore(const Core::Device& device, int fd) {
    // Imported external-FD semaphores must be created as ordinary binary
    // semaphores first. The external handle is attached by
    // vkImportSemaphoreFdKHR; advertising export capability here is not
    // required for the import path and can reject the create on drivers that
    // expose import but not export for this handle type.
    const VkSemaphoreCreateInfo desc{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
    };
    VkSemaphore semaphoreHandle{};
    auto res = vkCreateSemaphore(device.handle(), &desc, nullptr, &semaphoreHandle);
    if (res != VK_SUCCESS || semaphoreHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Unable to create semaphore");

    // import semaphore from fd
    auto vkImportSemaphoreFdKHR = reinterpret_cast<PFN_vkImportSemaphoreFdKHR>(
        vkGetDeviceProcAddr(device.handle(), "vkImportSemaphoreFdKHR"));

    const VkImportSemaphoreFdInfoKHR importInfo{
        .sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
        .semaphore = semaphoreHandle,
        .flags = static_cast<VkSemaphoreImportFlags>(
            externalFdHandleType == VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT
                ? VK_SEMAPHORE_IMPORT_TEMPORARY_BIT
                : 0u),
        .handleType = externalFdHandleType,
        .fd = fd // closes the fd
    };
    res = vkImportSemaphoreFdKHR(device.handle(), &importInfo);
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "Unable to import semaphore from fd");

    // store semaphore in shared ptr
    this->isTimeline = false;
    this->semaphore = std::shared_ptr<VkSemaphore>(
        new VkSemaphore(semaphoreHandle),
        [dev = device.handle()](VkSemaphore* semaphoreHandle) {
            vkDestroySemaphore(dev, *semaphoreHandle, nullptr);
        }
    );
}

void Semaphore::signal(const Core::Device& device, uint64_t value) const {
    if (!this->isTimeline)
        throw std::logic_error("Invalid timeline semaphore");

    const VkSemaphoreSignalInfo signalInfo{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO,
        .semaphore = this->handle(),
        .value = value
    };
    auto res = vkSignalSemaphore(device.handle(), &signalInfo);
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "Unable to signal semaphore");
}

bool Semaphore::wait(const Core::Device& device, uint64_t value, uint64_t timeout) const {
    if (!this->isTimeline)
        throw std::logic_error("Invalid timeline semaphore");

    VkSemaphore semaphore = this->handle();
    const VkSemaphoreWaitInfo waitInfo{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO,
        .semaphoreCount = 1,
        .pSemaphores = &semaphore,
        .pValues = &value
    };
    auto res = vkWaitSemaphores(device.handle(), &waitInfo, timeout);
    if (res != VK_SUCCESS && res != VK_TIMEOUT)
        throw LSFG::vulkan_error(res, "Unable to wait for semaphore");

    return res == VK_SUCCESS;
}
