#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:bismuthlib_light.glsl>
#moj_import <minecraft:colorful_fabric.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler7;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

in vec4 defaultVertex;
in vec3 colorMultiplier;
in vec3 blockPos;
in float skylight;
in vec3 meshNormal;

out vec4 fragColor;

// Block atlas sampling helpers from the vanilla 1.21.11 terrain shader (the atlas is bound with a linear sampler).
vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
	vec2 uvTexelCoords = uv / pixelSize;
	vec2 texelCenter = round(uvTexelCoords) - 0.5f;
	vec2 texelOffset = uvTexelCoords - texelCenter;
	texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
	texelOffset = clamp(texelOffset, 0.0f, 1.0f);
	uv = (texelCenter + texelOffset) * pixelSize;
	return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
	vec2 du = dFdx(uv);
	vec2 dv = dFdy(uv);
	vec2 texelScreenSize = sqrt(du * du + dv * dv);
	return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
	vec2 du = dFdx(uv);
	vec2 dv = dFdy(uv);
	vec2 texelScreenSize = sqrt(du * du + dv * dv);
	float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
	float minPixelSize = min(pixelSize.x, pixelSize.y);
	float blendFactor = smoothstep(minPixelSize, minPixelSize * 2.0, maxTexelSize);

	float duLength = length(du);
	float dvLength = length(dv);
	float effectiveDerivative = sqrt(min(duLength, dvLength) * max(duLength, dvLength));
	float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));
	float mipLevelLow = floor(mipLevelExact);
	float mipBlend = fract(mipLevelExact);

	const vec2 offsets[4] = vec2[](
		vec2(0.125, 0.375),
		vec2(-0.125, -0.375),
		vec2(0.375, -0.125),
		vec2(-0.375, 0.125)
	);

	vec4 rgssColorLow = vec4(0.0);
	vec4 rgssColorHigh = vec4(0.0);
	for (int i = 0; i < 4; ++i) {
		vec2 sampleUV = uv + offsets[i] * pixelSize;
		rgssColorLow += textureLod(source, sampleUV, mipLevelLow);
		rgssColorHigh += textureLod(source, sampleUV, mipLevelLow + 1.0);
	}
	vec4 rgssColor = mix(rgssColorLow * 0.25, rgssColorHigh * 0.25, mipBlend);
	vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
	return mix(nearestColor, rgssColor, blendFactor);
}

void main() {
	vec2 pixelSize = 1.0f / TextureSize;
	vec4 tex = UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, pixelSize) : sampleNearest(Sampler0, texCoord0, pixelSize);
#ifdef ALPHA_CUTOUT
	if (tex.a < ALPHA_CUTOUT) {
		discard;
	}
#endif
	// Exact texel alpha: emissive pixels are marked with alpha 254, which filtering would blur.
	float emissiveAlpha = texelFetch(Sampler0, ivec2(texCoord0 * TextureSize), 0).a;
	vec3 modelOffset = vec3(ChunkPosition - CameraBlockPos) + CameraOffset;
	vec4 color = addColoredLight(tex, vertexColor, tex, Sampler7, blockPos, playerSectionPos, modelOffset, dataScale, dataSide, skylight, defaultVertex, fastLight, colorMultiplier, meshNormal, lightsBrightness, emissiveAlpha);
	color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);
	fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
