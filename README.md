# Luma A17

Aplicativo Android nativo de câmera, voltado ao Samsung Galaxy A17 **SM-A175F/DS** (4G), sem root. Interface em português, fotos JPEG e vídeo MP4 salvos em `DCIM/LumaA17`.

Versão **1.1.0**, com perfil de luminosidade adaptativo após relato de capturas mais escuras que a câmera Samsung. **Não há promessa de qualidade igual à de um iPhone ou superior à câmera Samsung.** Não foi feita calibração de imagem em um A17 físico. O processamento proprietário da Apple não é transferível, e fabricantes podem reservar recursos para seu próprio aplicativo. Megapixels sozinhos não determinam qualidade.

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

O manifesto solicita apenas câmera e microfone. Microfone é pedido ao tentar gravar com som. Não há permissão de internet, root, localização, leitura ampla da galeria, telemetria, anúncios ou serviços em segundo plano. A gravação é encerrada quando o aplicativo sai do primeiro plano. Arquivos criados pelo aplicativo usam MediaStore; a última captura pode ser aberta no visualizador escolhido no Android. O diagnóstico não contém mídias, número de série ou contas.

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

Testes locais cobrem limites de exposição, obturador, rotação, bitrate, escolha de FPS, cenas escuras, altas luzes, superfície ainda sem imagem e estabilidade do ajuste de luminosidade. Compilação/lint não substituem os testes ópticos, térmicos e de estabilidade no telefone.

## Referências

- [Especificações oficiais do Galaxy A17 SM-A175F](https://www.samsung.com/br/smartphones/galaxy-a/galaxy-a17-gray-128gb-sm-a175fzafzto/): traseiras 50 + 5 + 2 MP, frontal 13 MP com foco fixo, OIS principal, gravação FHD a 30 fps.
- [Android CameraX](https://developer.android.com/media/camera/camerax)
- [Extensões de câmera](https://developer.android.com/media/camera/camerax/extensions-api)
- [Captura de vídeo](https://developer.android.com/media/camera/camerax/video-capture)

Projeto independente. Não afiliado à Samsung ou à Apple.
