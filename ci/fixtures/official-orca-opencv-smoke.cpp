#include "ObjColorUtils.hpp"

int main()
{
    QuantKMeans quantizer;
    return quantizer.m_alpha_thres == 10 ? 0 : 1;
}
