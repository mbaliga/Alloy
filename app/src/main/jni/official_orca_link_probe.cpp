// Link-only anchor for the separately compiled official Orca Android target.
// This library is not loaded by the app and exposes no slicing capability.
extern "C" __attribute__((visibility("default"))) int alloy_orca_link_probe() noexcept
{
    return 1;
}
