import 'dart:async';

import 'package:flutter/widgets.dart';

import 'h5pay_channel.dart';
import 'utils.dart';

enum PaymentStatus {
  idle,
  gettingPaymentUrl,
  getPaymentUrlTimeout,
  jumping,
  cantJump, // Maybe target payment app is not installed
  jumpTimeout,
  verifying,
  success,
  fail,
}

typedef Future<String> GetUrlCallback();
typedef Future<bool> VerifyResultCallback();
typedef Widget H5PayWidgetBuilder(
  BuildContext context,
  PaymentStatus status,
  H5PayController controller,
);

class H5PayController {
  final _launchNotifier = SimpleChangeNotifier();

  void launch() {
    _launchNotifier.notify();
  }

  void _dispose() {
    _launchNotifier.dispose();
  }
}

class H5PayWidget extends StatefulWidget {
  H5PayWidget({
    Key key,
    List<String> paymentSchemes,
    Duration getPaymentUrlTimeout,
    Duration jumpTimeout,
    @required this.getPaymentUrl,
    @required this.verifyResult,
    @required this.builder,
  })  : this.paymentSchemes =
            paymentSchemes ?? const ['alipay', 'alipays', 'weixin', 'wechat'],
        this.getPaymentUrlTimeout =
            getPaymentUrlTimeout ?? const Duration(seconds: 5),
        this.jumpTimeout = jumpTimeout ?? const Duration(seconds: 3),
        assert(getPaymentUrl != null),
        assert(verifyResult != null),
        assert(builder != null),
        super(key: key) {
    assert(!this.getPaymentUrlTimeout.isNegative);
    assert(!this.jumpTimeout.isNegative);
  }

  final List<String> paymentSchemes;
  final Duration getPaymentUrlTimeout;
  final Duration jumpTimeout;
  final GetUrlCallback getPaymentUrl;
  final VerifyResultCallback verifyResult;
  final H5PayWidgetBuilder builder;

  @override
  _H5PayWidgetState createState() => _H5PayWidgetState();
}

class _H5PayWidgetState extends State<H5PayWidget> with WidgetsBindingObserver {
  static const _checkJumpPeriod = Duration(milliseconds: 100);
  final _controller = H5PayController();

  PaymentStatus _status = PaymentStatus.idle;
  bool _listenLifecycle = false;
  bool _jumped = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _controller._launchNotifier.addListener(() async {
      _setPaymentStatus(PaymentStatus.gettingPaymentUrl);

      PaymentStatus failStatus = await _launch();
      if (failStatus != null || !mounted) {
        _setPaymentStatus(failStatus);
        return;
      }

      // Start to listen app lifecycle
      _listenLifecycle = true;
      _jumped = false;
      _setPaymentStatus(PaymentStatus.jumping);

      // Check if jump is successful
      failStatus = await _checkJump();
      if (failStatus != null || !mounted) {
        // Jump failed
        _listenLifecycle = false;
        _setPaymentStatus(failStatus);
        return;
      }
    });
  }

  @override
  void dispose() {
    _controller._dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) async {
    if (!_listenLifecycle) {
      return;
    }

    if (state == AppLifecycleState.inactive) {
      // Start to jump to payment app
      _jumped = true;
    } else if (state == AppLifecycleState.resumed) {
      // Resume from payment app
      _listenLifecycle = false;

      _setPaymentStatus(PaymentStatus.verifying);
      bool success;
      try {
        success = await widget.verifyResult();
      } catch (_) {
        success = false;
      }
      _setPaymentStatus(
          success == true ? PaymentStatus.success : PaymentStatus.fail);
    }
  }

  Future<PaymentStatus> _launch() async {
    String url;
    try {
      url = await widget.getPaymentUrl();
    } catch (_) {}
    if (url == null || url.isEmpty || !mounted) {
      return PaymentStatus.fail;
    }

    final completer = Completer<PaymentStatus>();
    void completeOnce(PaymentStatus status) {
      if (!completer.isCompleted) {
        completer.complete(status);
      }
    }

    H5PayChannel.launchPaymentUrl(url, widget.paymentSchemes).then((code) {
      PaymentStatus failStatus;
      switch (code) {
        case H5PayChannel.codeFailCantJump:
          failStatus = PaymentStatus.cantJump;
          break;
        case H5PayChannel.codeFail:
          failStatus = PaymentStatus.fail;
          break;
      }
      completeOnce(failStatus);

      //
    }).catchError((e) {
      debugPrint(e.toString());
      completeOnce(PaymentStatus.fail);
    });

    Future.delayed(widget.getPaymentUrlTimeout, () {
      completeOnce(PaymentStatus.getPaymentUrlTimeout);
    });

    return completer.future;
  }

  Future<PaymentStatus> _checkJump() async {
    final count =
        (widget.jumpTimeout.inMilliseconds / _checkJumpPeriod.inMilliseconds)
            .ceil();

    // Cycle check
    for (int i = 0; i < count; i++) {
      if (_jumped || !mounted) {
        return null;
      }
      await Future.delayed(_checkJumpPeriod);
    }

    return PaymentStatus.jumpTimeout;
  }

  void _setPaymentStatus(PaymentStatus status) {
    _status = status;
    if (mounted) {
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    return widget.builder(context, _status, _controller);
  }
}
