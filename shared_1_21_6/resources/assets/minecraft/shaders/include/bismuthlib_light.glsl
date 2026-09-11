#version 150

layout(std140) uniform BismuthLight {
	ivec3 playerSectionPos;
	ivec2 dataScale;
	int dataSide;
	int fastLight;
	float timeOfDay;
	float lightsBrightness;
};
