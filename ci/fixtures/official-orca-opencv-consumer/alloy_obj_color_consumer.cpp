#include "ObjColorUtils.hpp"

int main()
{
    std::vector<Slic3r::RGBA> input{
        {1.0f, 0.0f, 0.0f, 1.0f},
        {0.0f, 0.0f, 1.0f, 1.0f},
    };
    std::vector<Slic3r::RGBA> clusters;
    std::vector<int> labels;
    char cluster_count = 2;
    if (!obj_color_deal_algo(input, clusters, labels, cluster_count, 2))
        return 1;
    return clusters.size() == 2 && labels.size() == input.size() ? 0 : 2;
}
