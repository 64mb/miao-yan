#include <jni.h>

#include <cstring>
#include <limits>
#include <mutex>
#include <vector>

extern "C" {
#include "cmark-gfm-core-extensions.h"
#include "cmark-gfm-extension_api.h"
#include "cmark-gfm.h"
}

namespace {

constexpr const char *kExtensions[] = {
    "table",
    "strikethrough",
    "autolink",
    "tagfilter",
    "tasklist",
};

void Throw(JNIEnv *env, const char *class_name, const char *message) {
  jclass exception_class = env->FindClass(class_name);
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message);
  }
}

bool AttachGfmExtensions(cmark_parser *parser) {
  for (const char *name : kExtensions) {
    cmark_syntax_extension *extension = cmark_find_syntax_extension(name);
    if (extension == nullptr ||
        !cmark_parser_attach_syntax_extension(parser, extension)) {
      return false;
    }
  }
  return true;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tw93_miaoyan_android_ui_CmarkGfmNative_nativeRender(
    JNIEnv *env, jobject, jbyteArray markdown_utf8) {
  if (markdown_utf8 == nullptr) {
    Throw(env, "java/lang/NullPointerException", "markdownUtf8");
    return nullptr;
  }

  const jsize input_size = env->GetArrayLength(markdown_utf8);
  std::vector<char> input(static_cast<size_t>(input_size));
  if (input_size > 0) {
    env->GetByteArrayRegion(markdown_utf8, 0, input_size,
                            reinterpret_cast<jbyte *>(input.data()));
    if (env->ExceptionCheck()) {
      return nullptr;
    }
  }

  static std::once_flag registration;
  std::call_once(registration, cmark_gfm_core_extensions_ensure_registered);

  constexpr int options = CMARK_OPT_DEFAULT | CMARK_OPT_VALIDATE_UTF8;
  cmark_parser *parser = cmark_parser_new(options);
  if (parser == nullptr) {
    Throw(env, "java/lang/OutOfMemoryError", "Could not create cmark parser");
    return nullptr;
  }
  if (!AttachGfmExtensions(parser)) {
    cmark_parser_free(parser);
    Throw(env, "java/lang/IllegalStateException",
          "Could not attach cmark-gfm extensions");
    return nullptr;
  }

  cmark_parser_feed(parser, input.data(), input.size());
  cmark_node *document = cmark_parser_finish(parser);
  char *html = document == nullptr
                   ? nullptr
                   : cmark_render_html(document, options,
                                       cmark_parser_get_syntax_extensions(parser));
  if (html == nullptr) {
    if (document != nullptr) {
      cmark_node_free(document);
    }
    cmark_parser_free(parser);
    Throw(env, "java/lang/OutOfMemoryError", "Could not render Markdown");
    return nullptr;
  }

  const size_t output_size = std::strlen(html);
  if (output_size > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
    cmark_get_default_mem_allocator()->free(html);
    cmark_node_free(document);
    cmark_parser_free(parser);
    Throw(env, "java/lang/IllegalArgumentException", "Rendered HTML is too large");
    return nullptr;
  }

  jbyteArray result = env->NewByteArray(static_cast<jsize>(output_size));
  if (result != nullptr && output_size > 0) {
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(output_size),
                            reinterpret_cast<const jbyte *>(html));
  }

  cmark_get_default_mem_allocator()->free(html);
  cmark_node_free(document);
  cmark_parser_free(parser);
  return result;
}
