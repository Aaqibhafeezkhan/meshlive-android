"""Aligned pixel quality, explicit sample domains and valid SSIM windows."""

import math

import numpy as np

ALGORITHM = {
    "version":"vsg-quality-v1","psnr":"10*log10(255^2/MSE), all declared 8-bit code samples; identical pixels => inf",
    "ssim":"Luma Wang-style 11x11 Gaussian sigma=1.5, population covariance, K1=0.01,K2=0.03,data_range=255",
    "ssim_edges":"Valid windows only; every window touching excluded marker pixels is excluded for content SSIM",
    "alignment":"Exact native geometry, integer FPS, frame count and rational PTS; no resample/trim/drop/shift",
    "limits":"Synthetic detail/noise and PSNR/SSIM do not establish natural-viewing quality or phone performance",
}


def psnr(mse):
    return "inf" if mse==0 else 10*math.log10(255**2/mse)


def gaussian_valid(a):
    axis = np.arange(-5,6,dtype=np.float64)
    kernel = np.exp(-axis*axis/(2*1.5**2))
    kernel /= kernel.sum()
    horizontal = sum(kernel[i]*a[:,i:i+a.shape[1]-10] for i in range(11))
    return sum(kernel[i]*horizontal[i:i+a.shape[0]-10] for i in range(11))


def frame_quality(reference,decoded,width,height,mask):
    ref = np.frombuffer(reference,np.uint8).astype(np.float64)
    actual = np.frombuffer(decoded,np.uint8).astype(np.float64)
    if ref.shape!=actual.shape or len(ref)!=width*height*3//2:
        raise ValueError("Quality requires exactly aligned I420 frames")
    a,b = ref[:width*height].reshape(height,width),actual[:width*height].reshape(height,width)
    mse = float(np.mean((ref-actual)**2))
    mse_y = float(np.mean((a-b)**2))
    content_mse = float(np.mean((a-b)[mask]**2)) if mask.any() else None
    ssim,content_ssim = None,None
    if min(width,height)>=11:
        mu_a,mu_b = gaussian_valid(a),gaussian_valid(b)
        var_a = np.maximum(0,gaussian_valid(a*a)-mu_a*mu_a)
        var_b = np.maximum(0,gaussian_valid(b*b)-mu_b*mu_b)
        covariance = gaussian_valid(a*b)-mu_a*mu_b
        c1,c2 = (0.01*255)**2,(0.03*255)**2
        score = ((2*mu_a*mu_b+c1)*(2*covariance+c2))/((mu_a*mu_a+mu_b*mu_b+c1)*(var_a+var_b+c2))
        ssim = float(score.mean())
        valid = np.logical_and.reduce([mask[dy:dy+height-10,dx:dx+width-10] for dy in range(11) for dx in range(11)])
        if valid.any():
            content_ssim = float(score[valid].mean())
    return {"mse_i420":mse,"mse_y":mse_y,"psnr_i420":psnr(mse),"psnr_y":psnr(mse_y),
            "ssim_y":ssim,"content_mse_y":content_mse,"content_psnr_y":psnr(content_mse) if content_mse is not None else None,
            "content_ssim_y":content_ssim}
