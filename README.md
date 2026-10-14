# Luma A17

Aplicativo Android nativo de câmera, voltado ao Samsung Galaxy A17 **SM-A175F/DS** (4G), sem root. Interface em português, fotos JPEG/PNG e vídeo MP4 salvos em `DCIM/LumaA17`.

Versão **1.2.0**, com perfil de luminosidade adaptativo após relato de capturas mais escuras que a câmera Samsung. **Não há promessa de qualidade igual à de um iPhone ou superior à câmera Samsung.** Não foi feita calibração de imagem em um A17 físico. O processamento proprietário da Apple não é transferível, e fabricantes podem reservar recursos para seu próprio aplicativo. Megapixels sozinhos não determinam qualidade.

## Novidades da versão 1.2

O diagnóstico real enviado de um SM-A175F com Android 16 mostrou que o app 1.0 capturava em **1440 × 1080 (1,6 MP)**. Os JPEGs de **4080 × 3060 (12,5 MP)** da principal e **4128 × 3096 (12,8 MP)** da frontal aparecem apenas em `getHighResolutionOutputSizes`. Agora o perfil normal inclui essa lista e permite que CameraX escolha esses tamanhos. Um primeiro fallback conserva alta resolução com controles mais simples; somente o último fallback reduz resolução, com aviso. A resolução ativa aparece no visor e no diagnóstico. Precisa de confirmação de captura no aparelho físico.

- Visor em tela cheia com controles sobrepostos e barras do sistema ocultas. O modo ampliado recorta **somente a prévia** para preencher a tela; a foto continua inteira em 4:3 e o vídeo em 16:9. Desative a opção para conferir o quadro completo.
- **RETRATO** de pessoas com ML Kit Selfie Segmentation, modelo incluído no APK, executado localmente após capturar. Se não encontrar pessoa, conserva o original e informa o problema. Intensidade ajustável. Não simula um sensor de profundidade: cabelo, vidro e contornos podem falhar.
- O retrato compõe o assunto na resolução original, com desfoque calculado em uma versão pequena do fundo e máscara suavizada. A saída é **PNG SDR**, sem uma nova perda de compressão JPEG. O JPEG original, inclusive eventual ganho Ultra HDR, permanece intacto. Edições locais acima de 20 MP são recusadas em vez de silenciosamente reduzidas.
- Toque na miniatura para revisar a foto inteira, comparar original e última cópia, criar retrato, exportar PNG ou usar Gemini. PNG não recupera detalhe perdido no JPEG e não é RAW. Não há captura RAW/DNG nesta versão; o novo diagnóstico informa se o firmware libera RAW, câmeras físicas e grupos simultâneos.
- Gemini opcional com chave pessoal editável, criptografada por AES-GCM e chave do Android Keystore. Um ID `gen-lang-client-…` não autentica. Configure uma **API key** do Google AI Studio; o modelo e a saída 2K/4K também podem ser alterados.
- Gemini usa `POST /v1beta/interactions`, `store:false`, uma foto por ação confirmada, sem envio de vídeo, sem repetição automática paga e sem chave em URLs ou logs. O upload é limitado a 4096 pixels no lado maior e 15 MB, sem EXIF. Solicita PNG em 4K por padrão; a resolução e os detalhes finais dependem do modelo, que pode alterar a cena. O arquivo recebido é validado e salvo separadamente. Não é uma melhoria óptica nem um filtro em tempo real. Não foi testado com uma chave real.

O diagnóstico não demonstrou captura sincronizada/calibrada de duas lentes. Listar mais de uma câmera não significa poder fundi-las, nem somar megapixels. **Não há fusão de lentes, 4K de vídeo, ProRAW, ProRes, Live Photos, modo Cinema ou Deep Fusion**. A referência de interface e funções é o app Câmera da Apple; seus algoritmos e recursos exclusivos não foram copiados.

## Captura

- CameraX 1.5.3 sobre Camera2, com apenas prévia + foto **ou** prévia + vídeo ativos por vez.
- Câmeras frontal, principal e outras lentes que o Android efetivamente expuser. IDs não são presumidos.
- JPEG 100%, captura com prioridade à qualidade e resolução próxima de 12 MP por padrão. Opção de maior resolução exposta pelo aparelho; não promete acesso aos 50 MP completos. Ultra HDR é habilitado somente se a câmera da sessão declarar suporte; o JPEG continua compatível com visualizadores SDR.
- Mantém redução de ruído, nitidez e curva de tons escolhidas pelo fabricante para cada tipo de captura. Removidas as imposições globais de nitidez FAST e redução de ruído da versão anterior.
- AUTO, HDR e Noite usam extensões do fabricante e só aparecem para lentes compatíveis. O app inicia em AUTO quando disponível. Sem extensões, a captura normal permanece disponível. Não há HDR multiquadro próprio nem emulação de Deep Fusion/Smart HDR.
- Exposição inteligente mede uma amostra de 120 × 160 da superfície da prévia, sem adicionar um terceiro fluxo de câmera, salvar ou transmitir imagens. Compensa gradualmente cenas escuras, até +1 EV em fotos e +2/3 EV em vídeo; reduz o ajuste quando há risco de cortar altas luzes. A análise usa banda de tolerância e intervalo maior durante gravação para reduzir oscilações. Não substitui um processamento HDR local de sombras. O usuário pode assumir a barra de EV ou desativar o recurso.
- Vídeo prioriza Full HD e 24 Mb/s no novo perfil. Escolhe uma faixa anunciada pelo aparelho, preferindo 15–30 fps para permitir exposições mais longas em pouca luz. Há opção de priorizar 30 fps fixos, perfil 720p e fallback de compatibilidade. O codificador pode ajustar a taxa; não há upscaling, 4K fictício ou promessa de 60 fps. A opção de FPS adaptativo troca parte da fluidez por luz quando necessário.
- Low Light Boost do Android é solicitado para vídeo somente se suportado. O recurso é desativado antes de acender a lanterna; uma recusa do firmware mantém a exposição normal. Não é tratado como uma melhoria garantida do JPEG.
- OIS quando exposto pela lente; vídeo usa EIS compatível quando não há OIS. Nunca força OIS e EIS simultaneamente. Não há estabilização de software própria.
- Toque para foco/exposição, pressão longa para trava, compensação EV e zoom por pinça. A frontal do A17 tem foco fixo; tocar ajusta medição, não movimenta uma lente inexistente.
- Balanço de branco, anti-banding (60 Hz por padrão), foco manual e ISO/obturador em fotos, somente quando suportados. Controles manuais não interferem nas extensões HDR/Noite.
- Grade, nível, temporizador, selfie espelhada opcional, captura pelos botões de volume, pausa/retomada de vídeo e orientação de saída acompanhando o aparelho.
- Diagnóstico exportável de capacidades para permitir uma próxima rodada de calibração sem presumir o que o firmware libera.

Os ajustes Camera2 são solicitações ao fabricante, não garantia de imagem melhor. O modo de compatibilidade remove ajustes avançados se uma combinação não puder ser aberta.

## Privacidade

O manifesto solicita câmera, microfone e internet. Microfone é pedido ao tentar gravar com som. Fotos e vídeo usam MediaStore, sem leitura ampla da galeria ou localização. Captura e retrato não enviam fotos ao Gemini; somente o botão **Enviar esta foto** inicia essa transmissão. A API do Google pode ter cobrança e políticas próprias. Não há chave embutida ou backend compartilhado; esta integração BYOK é para uso pessoal. Para distribuir um serviço com chave própria, use um backend em vez de incluí-la no APK. A biblioteca ML Kit inclui um modelo local e pode coletar métricas operacionais conforme os termos do Google; não equivale a enviar a foto para edição na nuvem. O diagnóstico não contém mídia, chaves, número de série ou contas. Backups do aplicativo estão desativados.

Originais nunca são substituídos por edições. Cópias usam `IS_PENDING` e são publicadas na galeria somente ao concluir; falhas removem a entrada incompleta. O processamento pode ser interrompido se o Android encerrar o processo; o original já estará salvo.

## Compilação

Requer JDK 17, Android SDK 36, Build Tools 35.0.0 e internet para baixar as dependências. O Gradle Wrapper 8.13 está incluído. O aplicativo exige Android 10 ou superior; o alvo principal é o A17, incluindo o Android 16 do diagnóstico recebido.

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

Testes locais cobrem limites de exposição, obturador, rotação, bitrate, escolha de FPS, cenas escuras, altas luzes, superfície ainda sem imagem e estabilidade do ajuste de luminosidade. Compilação/lint não substituem os testes ópticos, térmicos e de estabilidade no telefone.

A suíte instrumentada cobre captura real em emulador, gravação reproduzível, pausa/retomada, revisão em tela cheia, PNG de 12,5 MP sem alteração de pixels, carregamento do modelo de retrato e cena sem pessoa, substituição/criptografia da chave e parsing de respostas Gemini simuladas. Os testes numéricos incluem a regressão dos tamanhos do diagnóstico real e a preservação dos pixels da pessoa no compositor. Nenhum teste paga uma solicitação Gemini. O teste com retrato de pessoas reais no A17 continua necessário para avaliar contornos e memória.

## Referências

- [Especificações oficiais do Galaxy A17 SM-A175F](https://www.samsung.com/br/smartphones/galaxy-a/galaxy-a17-gray-128gb-sm-a175fzafzto/): traseiras 50 + 5 + 2 MP, frontal 13 MP com foco fixo, OIS principal, gravação FHD a 30 fps.
- [ML Kit Selfie Segmentation](https://developers.google.com/ml-kit/vision/selfie-segmentation/android)
- [Gemini: edição de imagem](https://ai.google.dev/gemini-api/docs/image-generation)
- [App Câmera da Apple](https://apps.apple.com/br/app/c%C3%A2mera/id1584216193)
- [Android CameraX](https://developer.android.com/media/camera/camerax)
- [Extensões de câmera](https://developer.android.com/media/camera/camerax/extensions-api)
- [Captura de vídeo](https://developer.android.com/media/camera/camerax/video-capture)

Projeto independente. Não afiliado à Samsung ou à Apple.
