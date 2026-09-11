#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:bismuthlib_light.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

out vec3 blockPos;
out vec3 colorMultiplier;
out vec4 defaultVertex;
out float skylight;
out vec3 meshNormal;

vec4 minecraft_sample_lightmap(sampler2D lightMap, ivec2 uv) {
	return texture(lightMap, clamp((uv / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

void main() {
	vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
	gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

	meshNormal = Normal;
	skylight = clamp(UV2.y / 150.0, 0.0, 1.0) * clamp(sin(1.5707 - timeOfDay * 6.2831) * 3.0, 0.0, 1.0);
	defaultVertex = Color * minecraft_sample_lightmap(Sampler2, UV2);
	blockPos = Position;
	colorMultiplier = Color.rgb;

	sphericalVertexDistance = fog_spherical_distance(pos);
	cylindricalVertexDistance = fog_cylindrical_distance(pos);
	vertexColor = Color * minecraft_sample_lightmap(Sampler2, ivec2(0, UV2.y));
	texCoord0 = UV0;
}
