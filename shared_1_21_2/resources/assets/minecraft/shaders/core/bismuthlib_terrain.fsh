#version 150

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:colorful_fabric.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler7;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

uniform vec3 ModelOffset;
uniform ivec3 playerSectionPos;
uniform ivec2 dataScale;
uniform int dataSide;
uniform int fastLight;
uniform float lightsBrightness;

in vec4 defaultVertex;
in vec3 colorMultiplier;
in vec3 blockPos;
in float skylight;
in vec3 meshNormal;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
	vec4 tex = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
	if (tex.a < ALPHA_CUTOUT) {
		discard;
	}
#endif
	float emissiveAlpha = textureLod(Sampler0, texCoord0, 0.0).a;
	vec4 color = tex * ColorModulator;
	color = addColoredLight(color, vertexColor, tex, Sampler7, blockPos, playerSectionPos, ModelOffset, dataScale, dataSide, skylight, defaultVertex, fastLight, colorMultiplier, meshNormal, lightsBrightness, emissiveAlpha);
	fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
