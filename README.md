# Luma A17

Aplicativo Android nativo de câmera, voltado ao Samsung Galaxy A17 **SM-A175F/DS** (4G), sem root. Interface em português, fotos JPEG e vídeo MP4 salvos em `DCIM/LumaA17`.

Esta é uma primeira versão funcional para teste no aparelho. **Não há promessa de qualidade igual à de um iPhone ou superior à câmera Samsung.** Não foi feita calibração de imagem em um A17 físico. O processamento proprietário da Apple não é transferível, e fabricantes podem reservar recursos para seu próprio aplicativo. Megapixels sozinhos não determinam qualidade.

## Luma A17 1.3.0 · restauração do Work 1.2.0 + IA local

- Restaurada a **maior resolução JPEG disponibilizada pelo A17**, preferida por padrão, inclusive para quem utilizava versões 1.1.x. O diagnóstico da lente principal mostra 4080×3060 no caminho de alta resolução, não 50 MP; o resultado real precisa ser confirmado no arquivo salvo. Perfil compatível CameraX pode reduzir resolução se o HAL rejeitar a configuração.
- Modo **RETRATO**: segmentação neural pessoa/fundo com modelo `selfie_segmenter.tflite` oficial do MediaPipe, empacotado no APK pelo CI. Usa GPU quando o delegate inicia corretamente, com fallback CPU. Processamento **offline após a captura**; não há promessa de retrato em vídeo ao vivo.
- Filtros locais configuráveis **Natural, Equilibrado e Máximo**: medição de brilho, correção moderada de cores, suavização de ruído e nitidez controlada; são ajustes de pixels e **não** redes neurais de super-resolução. Podem ser aplicados automaticamente a uma cópia.
- Revisão pós-captura com ações **Original, Melhorar offline, Retrato, PNG, Gemini**. Original sempre preservado em `DCIM/LumaA17`; processamento local em `DCIM/LumaA17/Processadas`, IA Gemini em `DCIM/LumaA17/IA`.
- Exportação PNG sem novas perdas de compressão sobre os pixels já decodificados do JPEG. PNG **não equivale a RAW**, não resgata detalhes previamente perdidos e pode ocupar dezenas de MB.
- **Exportação HEIC opcional**: uma cópia é recodificada com AndroidX HeifWriter e qualidade solicitada 95. Precisa de codificador de HEIF compatível no dispositivo. Não recupera detalhes perdidos no JPEG e o JPEG original nunca é sobrescrito.
- Visor expandido com recorte de preenchimento, opção de desligar, exposição inicial +0,3 EV com controle manual EV no visor, funcionalidades das lentes frontal e principal mantidas.
- Vídeo continua limitado aos perfis reais do A17, com controle de bitrate e estabilização conforme capacidade da HAL, sem upscaling fake; áudio, pausa e retomada mantidos. **Não há fusão de múltiplas câmeras** sem suporte do fabricante e não há processamento generativo de vídeo em tempo real.
- Gemini mantido como **opção em nuvem separada**, nunca obrigatório. Requer API key e internet; pode modificar detalhes da imagem. Não são enviados arquivos ao Google pelo recurso offline.
- Captura direta HEIC/HEVC no CameraX não está implementada. Exportação HEIC de fotografia pronta é opcional e sujeita ao codec disponível; vídeo H.265/HEVC ainda não implementado. Não há conversão falsa por extensão.
- Build: JDK17 / Android SDK 36. A tarefa GitHub Actions baixa o modelo oficial de URL versionada `float16/1`, mostra SHA-256 no log, inclui dentro do APK e executa testes/lint. O código-fonte público NÃO contém tokens nem material privado.

### Como verificar

1. Na galeria confira as dimensões JPEG principal: com câmera livre do modo seguro, esperado até 4080×3060; comparar com a câmera Samsung.
2. Tire retrato **sem internet** e confirme se foi salva cópia em `Processadas`. Se falhar na GPU, o MediaPipe tentará CPU.
3. Exporte PNG e compare resolução e orientação, inclusive selfies.
4. Compare brilho, nitidez e detalhes entre Original e Natural. Na condição de luz desfavorável, desative ajustes agressivos.
5. Grave FHD por 30 s com áudio, em cada lente, pause/retome e confira galeria. O novo pós-processador não altera o vídeo original.
6. Meça aquecimento e tempo em processamento local automático. Desative se afetar a responsividade.

## Correção de autenticação e teste Gemini · versão 1.1.1

- Chaves do Google AI Studio iniciadas por `AQ.` **ou** `AIza` são aceitas sem bloqueio por prefixo. O Google migrou para chaves `AQ.`; o formato não é inválido por si só.
- **Testar conexão com Gemini** dentro dos Ajustes verifica uma chamada real e pequena de geração de texto pelo modelo `gemini-3.8-flash`, sem enviar fotos; pode consumir tokens e não confirma automaticamente o acesso ao modelo de imagens.
- Mostra respostas úteis para HTTP 400, 401, 403, 404, 429 e falhas do servidor, incluindo códigos públicos de erro, sem registrar credenciais.
- As fotografias são editadas por `gemini-3.1-flash-image`, solicitando imagem 2K e preservando a captura original no armazenamento do aparelho. A qualidade gerada por IA não substitui dados reais do sensor.
- Aplicar não depende do teste externo; em caso de erro ao cifrar a chave, outros ajustes da câmera ainda são salvos. O envio automático à IA permanece desligado caso a chave não seja armazenada corretamente.
- Chaves divulgadas em conversas devem ser substituídas por segurança. Nenhuma credencial é incluída no repositório ou APK.

## Melhorias da versão 1.1.0

- Fotografia em JPEG 97% ou 100% e maior resolução realmente exposta pela câmera. JPEG 100% gera arquivos maiores sem criar detalhes reais.
- Opção **Ultra HDR JPEG/R**, apenas se a lente declarar suporte; quando incompatível, volta para JPEG convencional. Ultra HDR não é HEIC.
- Vídeo na maior resolução real anunciada por lente (ou 720p opcional); bitrate de 16, 24 ou alvo de até 32 Mb/s para FHD. O dispositivo pode limitar a codificação. O Galaxy A17 4G anuncia Full HD 30 fps; NÃO há 4K fictício.
- **Gemini pós-captura para fotos**: botão manual ou envio automático por escolha explícita (desligado de fábrica). Envia a foto ao serviço Google, produz uma cópia em `DCIM/LumaA17/IA` e mantém o JPEG original inalterado.
- A API key do usuário é digitada nos ajustes e guardada cifrada via Android Keystore, nunca no repositório. Para usar, obtenha uma **chave de API válida do Google AI Studio** com acesso ao modelo `gemini-2.5-flash-image`. Tokens de autenticação de outros sistemas podem não funcionar. O envio pode consumir cota ou gerar cobrança.
- A IA generativa pode alterar detalhes, aparência e resolução da foto. **Não é restauração sem perdas de informação captada pelo sensor.** O resultado precisa ser conferido visualmente.
- A IA não é executada durante captura ou gravação. Não são enviados vídeos ao Gemini; isso não aperfeiçoaria automaticamente a cadeia de codificação, e edição quadro a quadro não garante continuidade temporal.
- **HEIC/HEIF e vídeo H.265/HEVC não estão implementados** neste build; as fotos originais continuam JPEG e vídeos MP4 com codec selecionado pelo CameraX 1.5.3. Não renomeie extensão para fingir outro codec. HEIC/HEVC são formas de compressão mais eficientes, não garantia de mais detalhes.
- Requer internet **somente para a função Gemini**. Sem configuração/consentimento, fotos e vídeos ficam locais.

## Captura

- CameraX 1.5.3 sobre Camera2, com apenas prévia + foto **ou** prévia + vídeo ativos por vez.
- Câmeras frontal, principal e outras lentes que o Android efetivamente expuser. IDs não são presumidos.
- JPEG 97% por padrão (100% opcional), captura com prioridade à qualidade e resolução próxima de 12 MP por padrão. Opção de maior resolução exposta pelo aparelho; não promete acesso aos 50 MP completos.
- Redução de ruído de alta qualidade solicitada para fotos; processamento rápido solicitado para vídeo, quando o HAL declara suporte. O fabricante ainda decide o processamento final.
- HDR e Noite usam extensões do fabricante e só aparecem para lentes compatíveis. Sem extensões, a captura normal permanece disponível. Não há HDR multiquadro próprio nem emulação de Deep Fusion/Smart HDR.
- Vídeo prioriza Full HD, alvo de 30 fps quando o intervalo fixo é anunciado, com 16, 24 ou 32 Mb/s solicitados. Perfil alternativo 720p e fallback de compatibilidade. O codificador pode ajustar a taxa; não há upscaling, 4K fictício ou promessa de 60 fps.
- OIS quando exposto pela lente; vídeo usa EIS compatível quando não há OIS. Nunca força OIS e EIS simultaneamente. Não há estabilização de software própria.
- Toque para foco/exposição, pressão longa para trava, compensação EV e zoom por pinça. A frontal do A17 tem foco fixo; tocar ajusta medição, não movimenta uma lente inexistente.
- Balanço de branco, anti-banding (60 Hz por padrão), foco manual e ISO/obturador em fotos, somente quando suportados. Controles manuais não interferem nas extensões HDR/Noite.
- Grade, nível, temporizador, selfie espelhada opcional, captura pelos botões de volume, pausa/retomada de vídeo e orientação de saída acompanhando o aparelho.
- Diagnóstico exportável de capacidades para permitir uma próxima rodada de calibração sem presumir o que o firmware libera.

Os ajustes Camera2 são solicitações ao fabricante, não garantia de imagem melhor. O modo de compatibilidade remove ajustes avançados se uma combinação não puder ser aberta.

## Privacidade

O manifesto solicita câmera, microfone e internet. A internet é usada somente na edição Gemini de fotos, que é opcional e desativada por padrão. Microfone é pedido ao tentar gravar com som. Não há root, localização, leitura ampla da galeria, telemetria, anúncios ou serviços em segundo plano. A gravação é encerrada quando o aplicativo sai do primeiro plano. Arquivos criados pelo aplicativo usam MediaStore; a última captura pode ser aberta no visualizador escolhido no Android. O diagnóstico não contém mídias, número de série ou contas.

## Compilação

Requer JDK 17, Android SDK 36, Build Tools 35.0.0 e internet para baixar as dependências. O Gradle Wrapper 8.13 está incluído. O aplicativo exige Android 10 ou superior; o alvo principal é o A17 com Android 15/One UI 7.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease
```

O APK de desenvolvimento fica em `app/build/outputs/apk/debug/`. Ele tem pacote separado (`com.ib2s.lumaa17.debug`) para não colidir com a versão instalada de produção.

Para gerar uma versão de distribuição assinada, mantenha a chave fora do repositório e configure `LUMA_KEYSTORE`, `LUMA_STORE_PASSWORD`, `LUMA_KEY_ALIAS` (padrão: `luma`) e, se diferente, `LUMA_KEY_PASSWORD`. Execute `./gradlew :app:assembleRelease`. Sem a chave, o Gradle gera um APK release não assinado. Nunca publique a chave ou a senha. Atualizações da versão `com.ib2s.lumaa17` precisam usar a mesma assinatura e `versionCode` crescente.

## Verificação no aparelho

1. Abra, permita câmera e fotografe com frontal e principal. Confira orientação, proporção e nitidez do arquivo na galeria.
2. Grave 30 segundos com áudio em cada câmera. Pause, retome e pare; confira sincronismo, foco, som e orientação horizontal/vertical.
3. Confira recusa de microfone, troca de lentes, timer cancelado, retorno do segundo plano e encerramento de vídeo ao sair do app.
4. Compare com a câmera Samsung usando a mesma luz, enquadramento, lente e resolução. Observe textura, rostos, áreas claras, sombras e objetos em movimento.
5. Exporte o diagnóstico para ajustes específicos do firmware. Não presuma que uma opção anunciada pelo sensor produz ganho visual.

Testes locais cobrem limites de exposição, obturador, rotação e política de bitrate. Compilação/lint não substituem os testes ópticos, térmicos e de estabilidade no telefone.

## Referências

- [Especificações oficiais do Galaxy A17 SM-A175F](https://www.samsung.com/br/smartphones/galaxy-a/galaxy-a17-gray-128gb-sm-a175fzafzto/): traseiras 50 + 5 + 2 MP, frontal 13 MP com foco fixo, OIS principal, gravação FHD a 30 fps.
- [Android CameraX](https://developer.android.com/media/camera/camerax)
- [Extensões de câmera](https://developer.android.com/media/camera/camerax/extensions-api)
- [Captura de vídeo](https://developer.android.com/media/camera/camerax/video-capture)

Projeto independente. Não afiliado à Samsung ou à Apple.
