#include "ObjColorUtils.hpp"

int main()
{
    QuantKMeans quantizer;
    cv::Mat pixels = cv::Mat::ones(2, 2, CV_8UC1);
    return quantizer.m_alpha_thres == 10 && cv::countNonZero(pixels) == 4 ? 0 : 1;
}
