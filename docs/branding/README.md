# 아이콘 제작 기록

노벨피아e의 아이콘은 공식 노벨피아 로고와 구별되는 책과 소문자 e를 사용한다.
내장 `imagegen` 도구로 생성했으며, 원본은 `icon-source.png`에 보존했다.
Android 아이콘과 README용 파일은 원본의 모양을 바꾸지 않고 크기만 변환했다.

- README: `docs/images/icon.png`
- Android: `app/src/main/res/mipmap-*/ic_launcher.png`
- 이 프로젝트의 아이콘 파일은 프로젝트와 같은 GPL-3.0 라이선스로 제공한다.

사용한 프롬프트:

> Use case: logo-brand. Asset type: Android launcher icon for an independent Korean e-ink reading app named 노벨피아e. Create one finished square icon, a bold black upright book silhouette on a solid white rounded-square tile, with a simple lowercase white 'e' formed naturally in the book cover and one restrained page-fold detail. Geometric, clean contours, generous margin, balanced optical weight, easily recognizable at 48 pixels and on a monochrome e-ink screen. Flat black and white only, no gradients, no shadows, no texture, no mockup, no extra words, no watermark. This is an independent reading app: invent an original mark and do not use or imitate Novelpia's official logo. Straight-on view, single centered icon, square 1024x1024 canvas.
