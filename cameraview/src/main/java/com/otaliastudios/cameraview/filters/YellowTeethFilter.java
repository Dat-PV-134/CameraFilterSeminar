package com.otaliastudios.cameraview.filters;

import androidx.annotation.NonNull;

import com.otaliastudios.cameraview.filter.BaseFilter;

/**
 * Yellow Teeth Filter: Detects yellow colors and keeps them, 
 * while converting other colors to grayscale.
 * This creates the popular "yellow teeth filter" effect.
 */
public class YellowTeethFilter extends BaseFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES sTexture;\n"
            + "varying vec2 "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+";\n"
            + "void main() {\n"
            + "  vec4 color = texture2D(sTexture, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+");\n"
            + "  \n"
            + "  // Calculate grayscale value\n"
            + "  float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));\n"
            + "  \n"
            + "  // Define yellow color range (HSV-based detection)\n"
            + "  float maxColor = max(max(color.r, color.g), color.b);\n"
            + "  float minColor = min(min(color.r, color.g), color.b);\n"
            + "  float delta = maxColor - minColor;\n"
            + "  \n"
            + "  // Calculate hue (simplified)\n"
            + "  float hue = 0.0;\n"
            + "  if (delta != 0.0) {\n"
            + "    if (maxColor == color.r) {\n"
            + "      hue = mod((color.g - color.b) / delta, 6.0);\n"
            + "    } else if (maxColor == color.g) {\n"
            + "      hue = (color.b - color.r) / delta + 2.0;\n"
            + "    } else {\n"
            + "      hue = (color.r - color.g) / delta + 4.0;\n"
            + "    }\n"
            + "    hue = hue * 60.0;\n"
            + "  }\n"
            + "  \n"
            + "  // Calculate saturation\n"
            + "  float saturation = maxColor == 0.0 ? 0.0 : delta / maxColor;\n"
            + "  \n"
            + "  // Yellow color range: hue between 45-75 degrees, with good saturation\n"
            + "  bool isYellow = hue >= 45.0 && hue <= 75.0 && saturation > 0.3 && maxColor > 0.2;\n"
            + "  \n"
            + "  // Alternative: RGB-based yellow detection (more permissive)\n"
            + "  bool isYellowRGB = color.r > 0.4 && color.g > 0.4 && color.b < 0.3 && \n"
            + "                     color.r > color.b && color.g > color.b;\n"
            + "  \n"
            + "  // Combine both detection methods\n"
            + "  bool isYellowColor = isYellow || isYellowRGB;\n"
            + "  \n"
            + "  // If it's yellow, keep the original color, otherwise use grayscale\n"
            + "  if (isYellowColor) {\n"
            + "    gl_FragColor = color;\n"
            + "  } else {\n"
            + "    gl_FragColor = vec4(gray, gray, gray, color.a);\n"
            + "  }\n"
            + "}\n";

    public YellowTeethFilter() { }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }
} 