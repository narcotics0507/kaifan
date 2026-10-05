const MAX_INPUT_BYTES = 30 * 1024 * 1024;
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

export function photoDimensions(width: number, height: number) {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0 || width * height > 80_000_000) throw new Error('照片尺寸异常或过大，请换一张普通照片。');
  const scale = Math.min(1, 1600 / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

/** Decode phone photos with their EXIF orientation, then upload a small, broadly supported JPEG. */
export async function prepareDishPhoto(file: File): Promise<File> {
  if (!file.size) throw new Error('这张照片是空文件，请重新选择。');
  if (file.size > MAX_INPUT_BYTES) throw new Error('照片超过 30MB，请选择普通照片或重新拍照。');
  if (!/^image\/(jpeg|png|webp|heic|heif)$/i.test(file.type) && !(file.type === '' && /\.(jpe?g|png|webp|heic|heif)$/i.test(file.name))) {
    throw new Error('请选择 JPG、PNG、WebP 或手机拍摄的照片。');
  }
  let source: ImageBitmap | HTMLImageElement | undefined;
  let url = '';
  try {
    try {
      source = await createImageBitmap(file, { imageOrientation: 'from-image' });
    } catch {
      url = URL.createObjectURL(file);
      source = await new Promise<HTMLImageElement>((resolve, reject) => {
        const image = new Image();
        const timer = setTimeout(() => reject(new Error('照片读取超时，请换一张照片。')), 15000);
        image.onload = () => { clearTimeout(timer); resolve(image); };
        image.onerror = () => { clearTimeout(timer); reject(new Error('这张照片无法读取，请重新拍照或选择 JPG / PNG 照片。')); };
        image.src = url;
      });
    }
    const width = source instanceof HTMLImageElement ? source.naturalWidth : source.width;
    const height = source instanceof HTMLImageElement ? source.naturalHeight : source.height;
    const dimensions = photoDimensions(width, height);
    const canvas = document.createElement('canvas');
    canvas.width = dimensions.width;
    canvas.height = dimensions.height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('照片处理失败，请重新选择。');
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(source, 0, 0, canvas.width, canvas.height);
    for (const quality of [0.86, 0.74, 0.6]) {
      const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', quality));
      if (blob && blob.size <= MAX_UPLOAD_BYTES) {
        return new File([blob], 'dish-photo.jpg', { type: 'image/jpeg', lastModified: Date.now() });
      }
    }
    throw new Error('照片处理后仍然过大，请重新拍照。');
  } finally {
    if (source && 'close' in source) source.close();
    if (url) URL.revokeObjectURL(url);
  }
}
