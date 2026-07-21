# Just4Fun2U

Leitor de quadrinhos e livros para Android — simples, leve e offline.

## Formatos suportados

| Formato | Suporte |
|---------|---------|
| CBZ     | ✅ Quadrinhos em ZIP |
| CBR     | ✅ Quadrinhos em RAR (RAR4; RAR5 não é suportado pela biblioteca junrar) |
| EPUB    | ✅ Livros (renderização por capítulo, capa, imagens e CSS do livro) |
| MOBI    | ✅ Livros sem DRM com compressão PalmDOC (a grande maioria). AZW com HUFF/CDIC ou DRM não são suportados |

## Recursos

- **Biblioteca com capas** — capa extraída automaticamente na importação
- **3 visualizações da biblioteca** — quadrados, lista ou círculos (botão no topo)
- **Coleções** — crie coleções, adicione/remova itens (segure um item), filtre por chips
- **Leitor de quadrinhos** — zoom por pinça, duplo toque, barra de navegação, lembra a última página
- **Modos de leitura** — virada de página estilo revista (3D), deslizar ou rolagem vertical livre
- **Filtros de leitura** — papel, vintage, sépia, preto e branco e modo daltônico (realce vermelho-verde), tanto para quadrinhos quanto para livros
- **Leitor de livros** — navegação por capítulos (EPUB), lembra capítulo e posição de rolagem
- **Leve** — sem frameworks pesados; APK release minificado com poucos MB
- Tema claro/escuro automático (Material 3)

## Como obter o APK

1. **GitHub Actions**: cada push gera o APK — veja a aba *Actions* → workflow *Build APK* → artefato `just4fun2u-apk`.
2. **Compilando localmente**:
   ```bash
   ./gradlew assembleRelease
   # resultado: app/build/outputs/apk/release/app-release.apk
   ```

Instale o APK no aparelho (é preciso permitir "instalar apps de fontes desconhecidas").

## Como usar

1. Toque em **Adicionar** e escolha arquivos `.cbr`, `.cbz`, `.epub` ou `.mobi`.
2. Os arquivos são copiados para o armazenamento do app (funciona 100% offline depois disso).
3. Toque para ler; segure um item para gerenciar coleções, renomear ou excluir.
4. Para criar uma coleção, toque no chip **+ Coleção** no topo da biblioteca.

## Notas técnicas

- minSdk 26 (Android 8.0+), targetSdk 34
- Kotlin + Views/Material 3 (sem Compose, para manter o APK pequeno)
- CBR via [junrar](https://github.com/junrar/junrar); EPUB e MOBI com parsers próprios
- Biblioteca persistida em JSON no armazenamento interno (sem banco de dados)
- O keystore em `signing/` serve apenas para assinar builds de instalação direta (sideload); não use para publicar em loja
